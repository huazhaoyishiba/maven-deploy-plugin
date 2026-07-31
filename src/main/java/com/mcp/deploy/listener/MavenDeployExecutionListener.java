package com.mcp.deploy.listener;

import com.intellij.execution.ExecutionListener;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.project.Project;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.openapi.diagnostic.Logger;
import com.mcp.deploy.settings.MavenDeploySettings;
import com.mcp.deploy.ssh.SshUploader;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

/**
 * 监听 Maven 打包完成 → 遍历 autoUpload=true 的服务器 → 逐个上传。
 */
public class MavenDeployExecutionListener implements ExecutionListener {

    private static final Logger LOG = Logger.getInstance(MavenDeployExecutionListener.class);

    private static final Pattern MAVEN_PACKAGE_PATTERN =
        Pattern.compile(".*\\[package|install|deploy].*", Pattern.CASE_INSENSITIVE);

    @Override
    public void processTerminated(@NotNull String executorId,
                                  @NotNull ExecutionEnvironment env,
                                  @NotNull ProcessHandler handler,
                                  int exitCode) {
        Project project = env.getProject();
        if (project == null) return;

        MavenDeploySettings settings = MavenDeploySettings.getInstance(project);
        if (!settings.pluginEnabled) return;

        // 判断是否是 Maven
        RunConfiguration runConfig = env.getRunnerAndConfigurationSettings() != null
            ? env.getRunnerAndConfigurationSettings().getConfiguration()
            : null;
        if (runConfig == null) return;

        String configName = runConfig.getName();
        String configTypeName = runConfig.getType().getId();
        boolean isMaven = configTypeName.contains("Maven") || configTypeName.contains("maven");

        if (!isMaven || !MAVEN_PACKAGE_PATTERN.matcher(configName).find()) return;

        LOG.info("检测到 Maven 打包完成: " + configName + ", exitCode=" + exitCode);

        if (exitCode != 0) {
            LOG.warn("Maven 打包失败, exitCode=" + exitCode);
            return;
        }

        handleBuildSuccess(project, settings);
    }

    private void handleBuildSuccess(Project project, MavenDeploySettings settings) {
        String basePath = project.getBasePath();
        if (basePath == null) {
            LOG.warn("无法获取项目根目录");
            return;
        }

        Path jarPath = findJarFile(basePath, settings);
        if (jarPath == null) {
            LOG.warn("未找到 jar 文件: " + basePath);
            return;
        }

        // 筛选启用了自动上传的服务器
        var targets = settings.servers.stream()
            .filter(s -> s.autoUpload)
            .toList();

        if (targets.isEmpty()) {
            LOG.info("没有启用自动上传的服务器，跳过");
            return;
        }

        LOG.info("找到 jar: " + jarPath + "，同时上传到 " + targets.size() + " 台服务器");

        final Path finalJar = jarPath;
        new Thread(() -> {
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(targets.size());
            for (MavenDeploySettings.ServerConfig server : targets) {
                new Thread(() -> {
                    try {
                        SshUploader.upload(project, settings, finalJar, server);
                    } catch (Exception e) {
                        LOG.error("上传到 " + server.getDisplayLabel() + " 失败", e);
                    } finally {
                        latch.countDown();
                    }
                }, "maven-deploy-upload-" + server.getDisplayLabel()).start();
            }
            try { latch.await(); } catch (InterruptedException ignored) {}
            LOG.info("批量上传完成: " + targets.size() + " 台");
        }, "maven-deploy-auto-upload").start();
    }

    private Path findJarFile(String basePath, MavenDeploySettings settings) {
        Path targetDir = Paths.get(basePath, "target");
        if (!Files.isDirectory(targetDir)) {
            try {
                return Files.walk(Paths.get(basePath), 4)
                    .filter(p -> p.toString().endsWith(".jar")
                        && !p.getFileName().toString().contains("-sources")
                        && !p.getFileName().toString().contains("-javadoc")
                        && p.getParent().getFileName().toString().equals("target"))
                    .findFirst()
                    .orElse(null);
            } catch (Exception e) {
                return null;
            }
        }

        try {
            if ("auto".equals(settings.jarNameMode)) {
                return Files.list(targetDir)
                    .filter(p -> p.toString().endsWith(".jar")
                        && !p.getFileName().toString().contains("-sources")
                        && !p.getFileName().toString().contains("-javadoc"))
                    .max((a, b) -> {
                        try { return Long.compare(Files.size(a), Files.size(b)); } catch (Exception e) { return 0; }
                    })
                    .orElse(null);
            } else {
                Path p = targetDir.resolve(settings.jarNameManual);
                if (Files.exists(p)) return p;
                if (!settings.jarNameManual.endsWith(".jar")) {
                    p = targetDir.resolve(settings.jarNameManual + ".jar");
                    if (Files.exists(p)) return p;
                }
                return null;
            }
        } catch (Exception e) {
            return null;
        }
    }
}
