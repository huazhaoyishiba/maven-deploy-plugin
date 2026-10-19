package com.mcp.deploy.listener;

import com.intellij.execution.ExecutionListener;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.notification.Notification;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.mcp.deploy.notify.DeployNotification;
import com.mcp.deploy.settings.MavenDeploySettings;
import com.mcp.deploy.ssh.SshUploader;
import com.mcp.deploy.util.JarResolver;
import com.mcp.deploy.util.MavenGoalParser;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 监听 Maven 运行结束事件，<b>仅在本次运行属于「打包」时</b>才遍历 autoUpload=true 的服务器并上传。
 *
 * <p>「打包」的判定顺序：</p>
 * <ol>
 *   <li>优先从 Maven 运行配置里读取真实 goal 列表（package / install / deploy 才算打包）；</li>
 *   <li>读不到时回退到运行配置名称解析（IDEA 名称形如 {@code module [clean, package]}）。</li>
 * </ol>
 *
 * <p>旧实现用正则 {@code .*\[package|install|deploy].*} 匹配名称，遇到
 * {@code demo [clean, package]} 这种多目标组合会匹配失败（表现为「有时候上传不了」）。</p>
 */
public class MavenDeployExecutionListener implements ExecutionListener {

    private static final Logger LOG = Logger.getInstance(MavenDeployExecutionListener.class);

    /** 同一份 jar 的重复触发抑制窗口（防止同一次构建被通知两次而重复上传）。 */
    private static final long DUPLICATE_WINDOW_MS = 5_000L;

    private static final Map<String, Long> RECENT_TRIGGERS = new ConcurrentHashMap<>();

    @Override
    public void processTerminated(@NotNull String executorId,
                                  @NotNull ExecutionEnvironment env,
                                  @NotNull ProcessHandler handler,
                                  int exitCode) {
        Project project = env.getProject();
        if (project == null) return;

        RunConfiguration runConfig = env.getRunnerAndConfigurationSettings() != null
            ? env.getRunnerAndConfigurationSettings().getConfiguration()
            : null;
        if (runConfig == null) {
            LOG.debug("执行结束但没有运行配置（可能不是 Run/Debug 方式启动），跳过自动上传");
            return;
        }

        String typeId = runConfig.getType().getId();
        if (typeId == null || !typeId.toLowerCase(Locale.ROOT).contains("maven")) return;

        MavenDeploySettings settings = MavenDeploySettings.getInstance(project);
        if (settings == null || !settings.pluginEnabled) return;

        String configName = runConfig.getName();
        List<String> goals = goalsOf(runConfig);

        boolean packaging;
        if (!goals.isEmpty()) {
            packaging = MavenGoalParser.hasPackagingGoal(goals);
        } else {
            // 读不到运行配置里的 goal（不同 IDE 版本 API 差异）时，退回名称判断
            packaging = MavenGoalParser.nameSuggestsPackaging(configName);
        }

        if (!packaging) {
            LOG.info("跳过自动上传：本次 Maven 运行不是打包 (config=" + configName
                + ", goals=" + goals + ")");
            return;
        }

        if (exitCode != 0) {
            LOG.warn("跳过自动上传：Maven 打包失败 exitCode=" + exitCode
                + " (config=" + configName + ")");
            return;
        }

        String hitGoal = goals.isEmpty()
            ? String.valueOf(MavenGoalParser.nameSuggestsPackaging(configName))
            : String.valueOf(MavenGoalParser.packagingGoal(goals));
        LOG.info("检测到 Maven 打包完成: config=" + configName
            + ", goals=" + goals + ", 命中打包目标=" + hitGoal + ", exitCode=0");

        handleBuildSuccess(project, settings, runConfig);
    }

    // =============================================
    //  打包成功后：定位 jar → 并发上传
    // =============================================

    private void handleBuildSuccess(Project project, MavenDeploySettings settings,
                                    RunConfiguration runConfig) {
        String basePath = project.getBasePath();
        if (basePath == null) {
            LOG.warn("无法获取项目根目录，跳过自动上传");
            return;
        }
        Path projectBase = Paths.get(basePath);
        Path moduleDir = workingDirOf(runConfig, projectBase);

        Path jarPath;
        try {
            // 固定规则：取最新构建的 jar（先本次构建的模块，再项目根，最后任意模块 target）
            jarPath = JarResolver.resolve(projectBase, moduleDir);
        } catch (Exception e) {
            LOG.warn("查找 jar 失败", e);
            jarPath = null;
        }

        if (jarPath == null) {
            String detail = buildJarNotFoundDetail(projectBase, moduleDir);
            LOG.warn("已打包成功，但没找到可上传的 jar。" + detail);
            notifyWarning(project, "Maven 部署未执行", detail);
            return;
        }

        List<MavenDeploySettings.ServerConfig> targets = settings.servers == null
            ? List.of()
            : settings.servers.stream().filter(s -> s.autoUpload).toList();

        if (targets.isEmpty()) {
            LOG.info("没有启用自动上传的服务器，跳过上传。jar=" + jarPath);
            return;
        }

        long modified = lastModified(jarPath);
        long size = sizeOf(jarPath);
        LOG.info("准备上传 jar=" + jarPath + " (" + size + " B, mtime="
            + java.time.Instant.ofEpochMilli(modified) + ") 到 " + targets.size() + " 台服务器");

        if (modified > 0 && System.currentTimeMillis() - modified > 10 * 60_000L) {
            LOG.warn("jar 的修改时间超过 10 分钟，可能不是本次构建产物: " + jarPath);
        }

        String triggerKey = runConfig.getName() + "|" + jarPath + "|" + modified + "|" + size;
        if (isDuplicateTrigger(triggerKey)) {
            LOG.info("同一份 jar 刚刚已经触发过上传，忽略本次重复事件: " + jarPath);
            return;
        }

        final Path finalJar = jarPath;
        Thread coordinator = new Thread(() -> uploadToServers(project, settings, finalJar, targets),
            "maven-deploy-auto-upload");
        coordinator.setDaemon(true);
        coordinator.start();
    }

    private void uploadToServers(Project project, MavenDeploySettings settings,
                                 Path jar, List<MavenDeploySettings.ServerConfig> targets) {
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(targets.size());

        // 先告诉用户「开始传了」，否则上传期间完全没有反馈
        List<String> labels = targets.stream()
            .map(MavenDeploySettings.ServerConfig::getDisplayLabel)
            .toList();
        Notification started = DeployNotification.showUploadStarted(
            project, jar.getFileName().toString(), sizeOf(jar), labels);

        for (MavenDeploySettings.ServerConfig server : targets) {
            Thread worker = new Thread(() -> {
                try {
                    if (SshUploader.upload(project, settings, jar, server)) {
                        success.incrementAndGet();
                    } else {
                        failed.incrementAndGet();
                    }
                } catch (Throwable t) {
                    failed.incrementAndGet();
                    LOG.error("上传到 " + server.getDisplayLabel() + " 异常", t);
                } finally {
                    latch.countDown();
                }
            }, "maven-deploy-upload-" + server.getDisplayLabel());
            worker.setDaemon(true);
            worker.start();
        }

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 收起「开始上传」通知，多台服务器时再给一条汇总
        if (started != null) {
            ApplicationManager.getApplication().invokeLater(() -> {
                try {
                    started.expire();
                } catch (Exception ignored) {
                }
            });
        }
        if (targets.size() > 1) {
            DeployNotification.showBatchResult(project, success.get(), failed.get(), targets.size());
        }

        LOG.info("批量上传结束: 成功 " + success.get() + " 台, 失败 " + failed.get()
            + " 台, 共 " + targets.size() + " 台, jar=" + jar);
    }

    // =============================================
    //  运行配置信息读取（反射，兼容不同 IDE 版本）
    // =============================================

    /**
     * 读取 Maven 运行配置里真实的 goal 列表。拿不到时返回空列表，由调用方回退到名称判断。
     */
    private static List<String> goalsOf(RunConfiguration runConfig) {
        try {
            Method getRunnerParameters = findMethod(runConfig.getClass(), "getRunnerParameters");
            if (getRunnerParameters == null) return List.of();
            Object parameters = getRunnerParameters.invoke(runConfig);
            if (parameters == null) return List.of();

            Method getGoals = findMethod(parameters.getClass(), "getGoals");
            if (getGoals == null) return List.of();
            Object rawGoals = getGoals.invoke(parameters);
            if (rawGoals instanceof Collection<?> collection) {
                List<String> goals = new ArrayList<>();
                for (Object goal : collection) {
                    if (goal != null) goals.add(String.valueOf(goal));
                }
                return MavenGoalParser.normalizeGoals(goals);
            }
        } catch (Throwable t) {
            LOG.debug("读取 Maven 运行配置 goals 失败，改用名称判断: " + t);
        }
        return List.of();
    }

    /**
     * 读取 Maven 运行配置的工作目录（通常就是被打包的模块目录），用于多模块项目精确定位 jar。
     */
    private static Path workingDirOf(RunConfiguration runConfig, Path projectBase) {
        try {
            Method getRunnerParameters = findMethod(runConfig.getClass(), "getRunnerParameters");
            if (getRunnerParameters == null) return null;
            Object parameters = getRunnerParameters.invoke(runConfig);
            if (parameters == null) return null;

            Method getWorkingDirPath = findMethod(parameters.getClass(), "getWorkingDirPath");
            if (getWorkingDirPath == null) return null;
            Object value = getWorkingDirPath.invoke(parameters);
            if (value instanceof String path && !path.isBlank()) {
                Path dir = Paths.get(path.trim());
                // 有些配置存的是相对项目根的路径
                if (!dir.isAbsolute() && projectBase != null) {
                    dir = projectBase.resolve(dir);
                }
                if (Files.isDirectory(dir)) return dir.normalize();
                LOG.debug("Maven 工作目录不存在: " + dir);
            }
        } catch (Throwable t) {
            LOG.debug("读取 Maven 工作目录失败: " + t);
        }
        return null;
    }

    /** 「找不到 jar」时给用户看的详细说明（含实际存在的候选，便于直接照着排查）。 */
    private static String buildJarNotFoundDetail(Path projectBase, Path moduleDir) {
        List<Path> found;
        try {
            found = JarResolver.listCandidatesFor(projectBase, moduleDir, 5);
        } catch (Exception e) {
            LOG.warn("列出候选 jar 失败", e);
            found = List.of();
        }

        Path searchDir = (moduleDir != null ? moduleDir : projectBase).resolve("target");

        StringBuilder sb = new StringBuilder("打包成功，但没有找到可上传的 jar。");
        sb.append("\n选取规则: target 下最新构建的可部署 jar");
        if (moduleDir != null && !moduleDir.equals(projectBase)) {
            sb.append("\n模块目录: ").append(moduleDir);
        }
        sb.append("\n搜索目录: ").append(searchDir);

        if (found.isEmpty()) {
            sb.append("\ntarget 下没有可部署的 jar（可能只剩 *-sources / *-javadoc / original-*，或还没打包）。");
            sb.append("\n请先执行一次 Maven package。");
        } else {
            sb.append("\n实际找到:");
            for (Path p : found) {
                sb.append("\n• ").append(p.getFileName());
            }
            sb.append("\n如果打包正常但仍报这个错，请把 idea.log 里的这段发给插件作者。");
        }
        return sb.toString();
    }

    private static Method findMethod(Class<?> type, String name) {
        for (Class<?> current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            try {
                Method method = current.getDeclaredMethod(name);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                // 继续往父类找
            }
        }
        return null;
    }

    // =============================================
    //  工具方法
    // =============================================

    private static boolean isDuplicateTrigger(String key) {
        long now = System.currentTimeMillis();
        RECENT_TRIGGERS.entrySet().removeIf(e -> now - e.getValue() > DUPLICATE_WINDOW_MS * 4);
        Long previous = RECENT_TRIGGERS.put(key, now);
        return previous != null && now - previous <= DUPLICATE_WINDOW_MS;
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (Exception e) {
            return 0L;
        }
    }

    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (Exception e) {
            return 0L;
        }
    }

    private static void notifyWarning(Project project, String title, String content) {
        try {
            DeployNotification.showWarning(project, title, content);
        } catch (Exception e) {
            LOG.warn("通知发送失败", e);
        }
    }
}
