package com.mcp.deploy.ssh;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.jcraft.jsch.*;
import com.mcp.deploy.settings.MavenDeploySettings;
import com.mcp.deploy.notify.DeployNotification;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * SSH/SFTP 上传器。
 * 支持密码认证和密钥认证，支持单服务器 / 批量上传。
 */
public final class SshUploader {

    private static final Logger LOG = Logger.getInstance(SshUploader.class);

    private SshUploader() {}

    // =============================================
    //  新旧兼容：单服务器上传（自动上传模式调用）
    // =============================================

    /**
     * 上传 jar 到一台服务器（调用 uploadToServer）。
     */
    public static void upload(Project project, MavenDeploySettings settings,
                              Path jarPath, MavenDeploySettings.ServerConfig server) {
        try {
            uploadToServer(project, settings, server, jarPath);

            // 通知
            long fileSize = Files.size(jarPath);
            String jarName = jarPath.getFileName().toString();
            String remotePath = server.remoteDir.endsWith("/")
                ? server.remoteDir + jarName
                : server.remoteDir + "/" + jarName;

            ApplicationManager.getApplication().invokeLater(() ->
                DeployNotification.showSuccess(project, jarName, remotePath, fileSize));
        } catch (Exception e) {
            LOG.error("上传失败: " + server.getDisplayLabel(), e);
            ApplicationManager.getApplication().invokeLater(() ->
                DeployNotification.showFailure(project, server.getDisplayLabel() + ": " + e.getMessage()));
        }
    }

    // =============================================
    //  核心：上传到单台服务器
    // =============================================

    /**
     * 上传 jar 到指定服务器。异常直接抛出。
     */
    public static void uploadToServer(Project project, MavenDeploySettings settings,
                                       MavenDeploySettings.ServerConfig server,
                                       Path jarPath) throws Exception {
        JSch jsch = new JSch();
        Session session = null;
        ChannelSftp sftpChannel = null;

        try {
            // 1. 认证
            if (!server.privateKeyPath.isEmpty()) {
                String keyPath = resolvePath(server.privateKeyPath);
                if (server.keyPassphrase.isEmpty()) {
                    jsch.addIdentity(keyPath);
                } else {
                    jsch.addIdentity(keyPath, server.keyPassphrase);
                }
                LOG.info("[" + server.getDisplayLabel() + "] 使用密钥认证: " + keyPath);
            }

            // 2. 会话
            session = jsch.getSession(server.user, server.host, server.port);
            if (!server.password.isEmpty()) {
                session.setPassword(server.password);
            }

            Properties config = new Properties();
            config.put("StrictHostKeyChecking", "no");
            config.put("PreferredAuthentications",
                server.privateKeyPath.isEmpty() ? "password" : "publickey,password");
            session.setConfig(config);
            session.setTimeout(settings.connectTimeout * 1000);

            LOG.info("[" + server.getDisplayLabel() + "] 连接中...");
            session.connect(settings.connectTimeout * 1000);

            // 3. SFTP
            Channel channel = session.openChannel("sftp");
            channel.connect(settings.connectTimeout * 1000);
            sftpChannel = (ChannelSftp) channel;

            // 4. 确保目录存在
            ensureRemoteDir(sftpChannel, server.remoteDir);

            // 5. 上传
            String jarFileName = jarPath.getFileName().toString();
            String remotePath = server.remoteDir.endsWith("/")
                ? server.remoteDir + jarFileName
                : server.remoteDir + "/" + jarFileName;

            LOG.info("[" + server.getDisplayLabel() + "] 上传: " + jarPath + " → " + remotePath);

            try (FileInputStream fis = new FileInputStream(jarPath.toFile())) {
                sftpChannel.put(fis, remotePath, ChannelSftp.OVERWRITE);
            }

            LOG.info("[" + server.getDisplayLabel() + "] 上传完成: " + remotePath);

            // 6. 远程命令
            String cmd = server.remoteCommand;
            if (cmd != null && !cmd.trim().isEmpty()) {
                executeRemoteCommand(session, cmd, settings.connectTimeout);
            }

        } finally {
            if (sftpChannel != null && sftpChannel.isConnected()) sftpChannel.disconnect();
            if (session != null && session.isConnected()) session.disconnect();
        }
    }

    // =============================================
    //  测试连接
    // =============================================

    /**
     * 测试连接（兼容旧 API，用 settings 里的旧字段）。
     */
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

    /**
     * 测试连接（使用 ServerConfig）。
     */
    public static String testConnection(MavenDeploySettings.ServerConfig server, int timeout) throws Exception {
        JSch jsch = new JSch();
        Session session = null;
        try {
            if (!server.privateKeyPath.isEmpty()) {
                String keyPath = resolvePath(server.privateKeyPath);
                if (server.keyPassphrase.isEmpty()) {
                    jsch.addIdentity(keyPath);
                } else {
                    jsch.addIdentity(keyPath, server.keyPassphrase);
                }
            }
            session = jsch.getSession(server.user, server.host, server.port);
            if (!server.password.isEmpty()) {
                session.setPassword(server.password);
            }
            Properties config = new Properties();
            config.put("StrictHostKeyChecking", "no");
            config.put("PreferredAuthentications",
                server.privateKeyPath.isEmpty() ? "password" : "publickey,password");
            session.setConfig(config);
            session.setTimeout(timeout * 1000);
            session.connect(timeout * 1000);
            return "连接成功 → " + server.getDisplayLabel();
        } finally {
            if (session != null && session.isConnected()) session.disconnect();
        }
    }

    // =============================================
    //  工具方法
    // =============================================

    private static void ensureRemoteDir(ChannelSftp sftp, String dir) throws SftpException {
        String[] parts = dir.split("/");
        StringBuilder current = new StringBuilder("/");
        for (String part : parts) {
            if (part.isEmpty()) continue;
            current.append(part).append("/");
            String path = current.toString();
            try {
                sftp.ls(path);
            } catch (SftpException e) {
                if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                    sftp.mkdir(path);
                    LOG.info("创建远程目录: " + path);
                } else {
                    throw e;
                }
            }
        }
    }

    private static void executeRemoteCommand(Session session, String command, int timeout)
        throws JSchException, IOException {
        ChannelExec execChannel = null;
        try {
            execChannel = (ChannelExec) session.openChannel("exec");
            execChannel.setCommand(command);
            execChannel.setInputStream(null);
            execChannel.setErrStream(System.err);
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(execChannel.getInputStream()))) {
                execChannel.connect(timeout * 1000);
                String line;
                while ((line = reader.readLine()) != null) {
                    LOG.info("远程命令输出: " + line);
                }
            }
            LOG.info("远程命令执行完成, exitStatus=" + execChannel.getExitStatus());
        } finally {
            if (execChannel != null && execChannel.isConnected()) execChannel.disconnect();
        }
    }

    private static String resolvePath(String path) {
        if (path.startsWith("~")) {
            return System.getProperty("user.home") + path.substring(1);
        }
        return path;
    }
}
