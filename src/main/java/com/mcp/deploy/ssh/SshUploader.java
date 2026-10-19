package com.mcp.deploy.ssh;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;
import com.jcraft.jsch.SftpProgressMonitor;
import com.mcp.deploy.notify.DeployNotification;
import com.mcp.deploy.settings.MavenDeploySettings;
import com.mcp.deploy.util.RemoteName;
import com.mcp.deploy.util.RemotePath;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * SSH/SFTP 上传器。
 *
 * <p>上传流程：连接 → 归一化远端目录（支持 {@code ~}、相对路径）→ 上传到
 * {@code xxx.jar.maven-deploy.tmp} → 校验大小 → {@code mv -f} 原子替换 → 执行远程命令。
 * 失败会重试（默认最多 3 次），并把 JSch 的原始错误翻译成可读原因。</p>
 */
public final class SshUploader {

    private static final Logger LOG = Logger.getInstance(SshUploader.class);

    /** 上传失败后的重试间隔（毫秒），长度即最大尝试次数。 */
    private static final long[] RETRY_DELAYS_MS = {0L, 1500L, 3000L};

    /** 上传过程中的临时文件名后缀，成功后原子替换。 */
    private static final String TMP_SUFFIX = ".maven-deploy.tmp";

    /** 传输阶段不限制 socket 读超时（0 = 不超时），避免大 jar 被读超时打断。 */
    private static final int TRANSFER_SOCKET_TIMEOUT_MS = 0;

    private static final int MIN_CONNECT_TIMEOUT_SECONDS = 5;
    private static final int MAX_CONNECT_TIMEOUT_SECONDS = 120;

    private SshUploader() {
    }

    // =============================================
    //  对外入口：带重试 + 通知
    // =============================================

    /**
     * 上传 jar 到一台服务器，内部重试并弹通知。不会抛异常。
     *
     * @return 是否上传成功
     */
    public static boolean upload(Project project, MavenDeploySettings settings,
                                 Path jarPath, MavenDeploySettings.ServerConfig server) {
        String label = server.getDisplayLabel();
        int maxAttempts = RETRY_DELAYS_MS.length;
        Exception lastError = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                String remotePath = uploadToServer(project, settings, server, jarPath);
                notifySuccess(project, server, jarPath, remotePath);
                return true;
            } catch (Exception e) {
                lastError = e;
                LOG.warn("[" + label + "] 第 " + attempt + "/" + maxAttempts + " 次上传失败: " + e.getMessage(), e);
                // 配置错误（主机/认证信息缺失、本地文件不存在）重试也没用，直接结束
                if (e instanceof IllegalArgumentException || e instanceof FileNotFoundException) {
                    break;
                }
                if (attempt < maxAttempts) {
                    sleepQuietly(RETRY_DELAYS_MS[attempt]);
                }
            }
        }

        final String message = lastError == null ? "未知错误" : String.valueOf(lastError.getMessage());
        LOG.error("[" + label + "] 上传失败（已重试 " + (maxAttempts - 1) + " 次）: " + message, lastError);
        notifyFailure(project, label, message);
        return false;
    }

    // =============================================
    //  核心：上传到单台服务器（单次尝试，异常外抛）
    // =============================================

    /**
     * 上传 jar 到指定服务器。任何失败都直接抛出，由调用方决定重试与提示。
     *
     * @return 远端最终文件路径
     */
    public static String uploadToServer(Project project, MavenDeploySettings settings,
                                        MavenDeploySettings.ServerConfig server,
                                        Path jarPath) throws Exception {
        if (jarPath == null || !Files.isRegularFile(jarPath)) {
            throw new FileNotFoundException("本地 jar 不存在: " + jarPath);
        }
        if (isBlank(server.host)) throw new IllegalArgumentException("未配置 SSH 主机");
        if (isBlank(server.user)) throw new IllegalArgumentException("未配置 SSH 用户名");
        if (isBlank(server.password) && isBlank(server.privateKeyPath)) {
            throw new IllegalArgumentException("未配置认证信息：SSH 密码和私钥至少填一个");
        }

        long localSize = Files.size(jarPath);
        if (localSize <= 0L) throw new IOException("本地 jar 为空文件: " + jarPath);

        String label = server.getDisplayLabel();
        int port = server.port > 0 ? server.port : 22;
        int timeoutMs = connectTimeoutMs(settings);

        JSch jsch = new JSch();
        Session session = null;
        ChannelSftp sftpChannel = null;

        try {
            configureIdentity(jsch, server, label);

            session = jsch.getSession(server.user.trim(), server.host.trim(), port);
            if (!isBlank(server.password)) {
                session.setPassword(server.password);
            }
            session.setConfig(buildSessionConfig(server));
            session.setTimeout(timeoutMs);

            LOG.info("[" + label + "] 连接 " + server.host + ":" + port + " ...");
            session.connect(timeoutMs);

            // 连接建立后取消读超时：SFTP 传输时等待服务端 ACK 的时间不受该值约束，
            // 否则大文件在慢速网络上会被 SocketTimeoutException 打断（旧版本的偶发失败原因之一）。
            session.setTimeout(TRANSFER_SOCKET_TIMEOUT_MS);

            Channel channel = session.openChannel("sftp");
            channel.connect(timeoutMs);
            sftpChannel = (ChannelSftp) channel;

            String homeDir = remoteHome(sftpChannel, label);
            // 远程目录留空时按登录用户的 home 目录处理，避免直接把 jar 丢到 / 下
            String configuredDir = isBlank(server.remoteDir) ? "~" : server.remoteDir;
            String remoteDir = RemotePath.expandHome(configuredDir, homeDir);
            ensureRemoteDir(sftpChannel, remoteDir);

            String jarName = jarPath.getFileName().toString();
            String remoteName;
            try {
                remoteName = RemoteName.resolve(server.remoteFileName, jarName);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("远端文件名配置有误（" + e.getMessage()
                    + "），请检查「远端文件名」设置");
            }
            if (!remoteName.equals(jarName)) {
                LOG.info("[" + label + "] 远端重命名: " + jarName + " → " + remoteName);
            }
            String remotePath = RemotePath.join(remoteDir, remoteName);
            String tmpPath = remotePath + TMP_SUFFIX;

            LOG.info("[" + label + "] 上传 " + jarPath + " (" + localSize + " B) → " + remotePath);
            try (InputStream in = new BufferedInputStream(Files.newInputStream(jarPath), 128 * 1024)) {
                sftpChannel.put(in, tmpPath, new UploadProgress(label, localSize), ChannelSftp.OVERWRITE);
            }

            long uploadedSize = sftpChannel.lstat(tmpPath).getSize();
            if (uploadedSize != localSize) {
                throw new IOException("上传不完整：本地 " + localSize + " B，远端 " + uploadedSize + " B");
            }

            replaceRemoteFile(session, sftpChannel, tmpPath, remotePath, timeoutMs, label);
            LOG.info("[" + label + "] 上传完成: " + remotePath + " (" + localSize + " B)");

            String cmd = server.remoteCommand;
            if (!isBlank(cmd)) {
                int exit = execRemoteCommand(session, cmd, timeoutMs, label);
                if (exit != 0) {
                    LOG.warn("[" + label + "] 远程命令返回非 0: " + exit);
                }
            }
            return remotePath;
        } catch (JSchException | SftpException e) {
            throw new IOException(describeFailure(server, e), e);
        } finally {
            if (sftpChannel != null && sftpChannel.isConnected()) {
                try {
                    sftpChannel.disconnect();
                } catch (Exception ignored) {
                }
            }
            if (session != null && session.isConnected()) {
                try {
                    session.disconnect();
                } catch (Exception ignored) {
                }
            }
        }
    }

    // =============================================
    //  测试连接
    // =============================================

    /** 测试连接（兼容旧 API，用 settings 里的旧字段）。 */
    public static String testConnection(MavenDeploySettings settings) throws Exception {
        MavenDeploySettings.ServerConfig s = new MavenDeploySettings.ServerConfig();
        s.host = settings.sshHost;
        s.port = settings.sshPort;
        s.user = settings.sshUser;
        s.password = settings.sshPassword;
        s.privateKeyPath = settings.sshPrivateKeyPath;
        s.keyPassphrase = settings.sshKeyPassphrase;
        return testConnection(s, settings.connectTimeout);
    }

    /** 测试连接（使用 ServerConfig）。 */
    public static String testConnection(MavenDeploySettings.ServerConfig server, int timeoutSeconds) throws Exception {
        if (isBlank(server.host)) throw new IllegalArgumentException("请先填写主机地址");
        if (isBlank(server.user)) throw new IllegalArgumentException("请先填写用户名");
        if (isBlank(server.password) && isBlank(server.privateKeyPath)) {
            throw new IllegalArgumentException("请填写密码或私钥");
        }

        int timeoutMs = connectTimeoutMs(timeoutSeconds);
        JSch jsch = new JSch();
        Session session = null;
        ChannelSftp sftp = null;
        try {
            configureIdentity(jsch, server, server.getDisplayLabel());
            session = jsch.getSession(server.user.trim(), server.host.trim(),
                server.port > 0 ? server.port : 22);
            if (!isBlank(server.password)) session.setPassword(server.password);
            session.setConfig(buildSessionConfig(server));
            session.setTimeout(timeoutMs);
            session.connect(timeoutMs);

            // 顺带确认 SFTP 子系统和 home 目录可用（上传依赖它们）
            Channel channel = session.openChannel("sftp");
            channel.connect(timeoutMs);
            sftp = (ChannelSftp) channel;
            String home = remoteHome(sftp, server.getDisplayLabel());
            return "连接成功 → " + server.getDisplayLabel() + "（home: " + home + "）";
        } catch (JSchException e) {
            throw new IOException(describeFailure(server, e), e);
        } finally {
            if (sftp != null && sftp.isConnected()) sftp.disconnect();
            if (session != null && session.isConnected()) session.disconnect();
        }
    }

    // =============================================
    //  内部实现
    // =============================================

    private static void configureIdentity(JSch jsch, MavenDeploySettings.ServerConfig server, String label)
        throws JSchException, IOException {
        if (isBlank(server.privateKeyPath)) return;

        String keyPath = resolveLocalPath(server.privateKeyPath.trim());
        if (!new File(keyPath).isFile()) {
            throw new IOException("私钥文件不存在: " + keyPath);
        }
        if (isBlank(server.keyPassphrase)) {
            jsch.addIdentity(keyPath);
        } else {
            jsch.addIdentity(keyPath, server.keyPassphrase);
        }
        LOG.info("[" + label + "] 使用密钥认证: " + keyPath);
    }

    private static Properties buildSessionConfig(MavenDeploySettings.ServerConfig server) {
        Properties config = new Properties();
        config.put("StrictHostKeyChecking", "no");
        config.put("PreferredAuthentications", isBlank(server.privateKeyPath)
            ? "password,keyboard-interactive"
            : "publickey,password,keyboard-interactive");
        return config;
    }

    private static String remoteHome(ChannelSftp sftp, String label) {
        try {
            String home = sftp.getHome();
            if (!isBlank(home)) return RemotePath.normalizeDir(home);
        } catch (SftpException e) {
            LOG.warn("[" + label + "] 获取远端 home 目录失败，按 / 处理: " + e.getMessage());
        }
        return "/";
    }

    /**
     * 逐级创建远端目录。用 lstat 判断存在性（比 ls 更省流量，且不受权限回显影响）。
     */
    private static void ensureRemoteDir(ChannelSftp sftp, String dir) throws SftpException {
        String normalized = RemotePath.normalizeDir(dir);
        if ("/".equals(normalized)) return;

        String[] parts = normalized.split("/");
        StringBuilder current = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            current.append('/').append(part);
            String path = current.toString();
            try {
                sftp.lstat(path);
            } catch (SftpException e) {
                if (e.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) throw e;
                try {
                    sftp.mkdir(path);
                    LOG.info("创建远程目录: " + path);
                } catch (SftpException mkdirError) {
                    // 并发/权限等原因导致创建失败时再确认一次，确实不存在才向外抛
                    try {
                        sftp.lstat(path);
                    } catch (SftpException stillMissing) {
                        throw mkdirError;
                    }
                }
            }
        }
    }

    /**
     * 把临时文件原子替换为目标文件：优先 {@code mv -f}，失败回退 SFTP rename。
     *
     * <p>注意：{@code mv} 的结果可能不确定（命令已执行但没读到退出码），此时必须先用
     * {@code lstat} 判断临时文件是否还在，避免把刚替换好的目标文件又删掉。</p>
     */
    private static void replaceRemoteFile(Session session, ChannelSftp sftp,
                                          String tmpPath, String remotePath, int timeoutMs, String label)
        throws JSchException, SftpException, IOException {
        String command = "mv -f '" + shellEscape(tmpPath) + "' '" + shellEscape(remotePath) + "'";
        boolean moved = false;
        try {
            int status = execRemoteCommand(session, command, timeoutMs, label);
            moved = status == 0;
            if (!moved) {
                LOG.warn("[" + label + "] mv 返回 " + status + "，回退为 SFTP rename: " + tmpPath);
            }
        } catch (Exception e) {
            LOG.warn("[" + label + "] mv 执行失败，回退为 SFTP rename: " + e.getMessage());
        }
        if (moved) return;

        // mv 结果不确定：先确认临时文件状态，避免误删已经替换好的目标文件
        if (!remoteFileExists(sftp, tmpPath)) {
            if (remoteFileExists(sftp, remotePath)) {
                LOG.info("[" + label + "] 临时文件已不存在且目标文件存在，视为替换成功: " + remotePath);
                return;
            }
            throw new IOException("上传失败：临时文件与目标文件都不存在（" + tmpPath + "）");
        }

        try {
            sftp.rm(remotePath);
        } catch (SftpException ignored) {
            // 目标文件本来就不存在
        }
        try {
            sftp.rename(tmpPath, remotePath);
        } catch (SftpException e) {
            LOG.error("重命名失败，临时文件保留在 " + tmpPath, e);
            throw new IOException("远端重命名失败（临时文件已保留在 " + tmpPath + "）: " + e.getMessage(), e);
        }
    }

    private static boolean remoteFileExists(ChannelSftp sftp, String path) {
        try {
            sftp.lstat(path);
            return true;
        } catch (SftpException e) {
            return false;
        }
    }

    private static int execRemoteCommand(Session session, String command, int timeoutMs, String label)
        throws JSchException, IOException {
        ChannelExec execChannel = null;
        try {
            execChannel = (ChannelExec) session.openChannel("exec");
            execChannel.setCommand(command);
            execChannel.setInputStream(null);
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            execChannel.setErrStream(stderr);

            InputStream stdout = execChannel.getInputStream();
            execChannel.connect(timeoutMs);

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stdout, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    LOG.info("[" + label + "] 远程输出: " + line);
                    if (output.length() < 8192) output.append(line).append('\n');
                }
            }

            // 读完输出后 channel 可能还没关，等一下状态码
            long deadline = System.currentTimeMillis() + Math.max(timeoutMs, 10_000);
            while (!execChannel.isClosed() && System.currentTimeMillis() < deadline) {
                sleepQuietly(20L);
            }

            String errText = stderr.toString(StandardCharsets.UTF_8).trim();
            if (!errText.isEmpty()) {
                LOG.warn("[" + label + "] 远程 stderr: " + errText);
            }
            int status = execChannel.getExitStatus();
            if (status != 0) {
                LOG.warn("[" + label + "] 远程命令退出码 " + status
                    + (output.length() > 0 ? "，输出: " + output : "")
                    + (errText.isEmpty() ? "" : "，错误: " + errText));
            }
            return status;
        } finally {
            if (execChannel != null && execChannel.isConnected()) {
                try {
                    execChannel.disconnect();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** 把 JSch 的原始错误翻译成用户能看懂的提示。 */
    private static String describeFailure(MavenDeploySettings.ServerConfig server, Exception e) {
        String raw = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        String lower = raw.toLowerCase();

        if (e instanceof SftpException sftpException) {
            switch (sftpException.id) {
                case ChannelSftp.SSH_FX_NO_SUCH_FILE:
                    return "远端路径不存在或不可访问: " + raw;
                case ChannelSftp.SSH_FX_PERMISSION_DENIED:
                    return "远端权限不足（请检查目录属主/权限）: " + raw;
                case ChannelSftp.SSH_FX_FAILURE:
                    return lower.contains("permission")
                        ? "远端权限不足（请检查目录属主/权限）: " + raw
                        : "SFTP 操作失败: " + raw;
                default:
                    return "SFTP 错误(" + sftpException.id + "): " + raw;
            }
        }
        if (lower.contains("auth fail") || lower.contains("auth cancel")) {
            return "认证失败：用户名/密码或私钥不正确（主机 " + server.host + "，用户 " + server.user + "）";
        }
        if (lower.contains("algorithm negotiation fail")) {
            return "SSH 算法协商失败：服务器可能禁用了旧算法，请确认使用密码认证或新版私钥算法（如 ed25519）";
        }
        if (lower.contains("invalid privatekey")) {
            return "私钥格式无法识别：请确认私钥未加密或使用 OpenSSH/PEM 格式（如 id_ed25519 / id_rsa）";
        }
        if (lower.contains("timeout") || lower.contains("timed out")) {
            return "连接超时：请检查主机/端口/网络/防火墙（当前超时 " + server.host + "）";
        }
        if (lower.contains("connection refused")) {
            return "连接被拒绝：请检查主机/端口是否正确、sshd 是否开放（" + server.host + ":" + server.port + "）";
        }
        if (lower.contains("unknownhost")) {
            return "主机名无法解析: " + server.host;
        }
        if (lower.contains("no route to host")) {
            return "网络不可达: " + server.host;
        }
        if (lower.contains("packet corrupt") || lower.contains("connection reset")) {
            return "连接被重置，可能是网络抖动或服务器主动断开: " + raw;
        }
        return raw;
    }

    private static String resolveLocalPath(String path) {
        if (path.startsWith("~")) {
            return System.getProperty("user.home") + path.substring(1);
        }
        return path;
    }

    private static String shellEscape(String s) {
        return s.replace("'", "'\\''");
    }

    private static int connectTimeoutMs(MavenDeploySettings settings) {
        return connectTimeoutMs(settings == null ? 0 : settings.connectTimeout);
    }

    private static int connectTimeoutMs(int seconds) {
        int s = seconds > 0 ? seconds : 10;
        s = Math.max(MIN_CONNECT_TIMEOUT_SECONDS, Math.min(MAX_CONNECT_TIMEOUT_SECONDS, s));
        return s * 1000;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // =============================================
    //  通知
    // =============================================

    private static void notifySuccess(Project project, MavenDeploySettings.ServerConfig server,
                                      Path jarPath, String remotePath) {
        try {
            long size = Files.size(jarPath);
            String jarName = jarPath.getFileName().toString();
            String label = server.getDisplayLabel();
            runOnEdt(() -> DeployNotification.showSuccess(project, label, jarName, remotePath, size));
        } catch (Exception e) {
            LOG.warn("上传成功通知发送失败", e);
        }
    }

    private static void notifyFailure(Project project, String serverLabel, String message) {
        try {
            runOnEdt(() -> DeployNotification.showFailure(project, serverLabel, message));
        } catch (Exception e) {
            LOG.warn("上传失败通知发送失败", e);
        }
    }

    private static void runOnEdt(Runnable task) {
        try {
            ApplicationManager.getApplication().invokeLater(task);
        } catch (Exception e) {
            LOG.warn("无法调度到 EDT，直接执行", e);
            task.run();
        }
    }

    /** 上传进度：每 10% 打一条日志，方便排查「上传卡住 / 中断」。 */
    private static final class UploadProgress implements SftpProgressMonitor {
        private final String label;
        private final long total;
        private int lastPercent = -1;
        private long startedAt;

        private UploadProgress(String label, long total) {
            this.label = label;
            this.total = total;
        }

        @Override
        public void init(int op, String src, String dest, long max) {
            startedAt = System.currentTimeMillis();
            LOG.info("[" + label + "] 开始传输 → " + dest + (total > 0 ? " (" + total + " B)" : ""));
        }

        @Override
        public boolean count(long bytes) {
            if (total <= 0) return true;
            int percent = (int) Math.min(100L, bytes * 100L / total);
            if (percent >= lastPercent + 10 || percent == 100) {
                lastPercent = percent;
                LOG.info("[" + label + "] 上传进度 " + percent + "% (" + bytes + "/" + total + " B)");
            }
            return true;
        }

        @Override
        public void end() {
            long cost = System.currentTimeMillis() - startedAt;
            LOG.info("[" + label + "] 传输结束，耗时 " + cost + " ms");
        }
    }
}
