package com.mcp.deploy.settings;

import com.intellij.openapi.components.*;
import com.intellij.openapi.project.Project;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 插件配置持久化存储。
 * 支持多个服务器配置，每项独立开关。
 */
@State(
    name = "MavenDeploySettings",
    storages = @Storage("maven-deploy-plugin.xml")
)
public final class MavenDeploySettings implements PersistentStateComponent<MavenDeploySettings> {

    // ========== 全局设置 ==========
    /** Jar 名称模式：auto=自动, manual=手动指定 */
    public String jarNameMode = "auto";
    /** 手动指定的 Jar 名称 */
    public String jarNameManual = "app.jar";
    /** 全局连接超时（秒） */
    public int connectTimeout = 10;
    /** 是否总开关（控制整个插件） */
    public boolean pluginEnabled = true;

    // ========== 服务器配置列表 ==========
    public List<ServerConfig> servers = new ArrayList<>();

    // ========== 内部类：单条服务器配置 ==========

    public static class ServerConfig {
        /** 唯一标识，用于编辑/删除 */
        public String id = "";
        /** 配置名称，用于列表展示 */
        public String name = "";
        /** SSH 主机 */
        public String host = "";
        /** SSH 端口 */
        public int port = 22;
        /** SSH 用户名 */
        public String user = "root";
        /** SSH 密码（为空则用密钥） */
        public String password = "";
        /** SSH 私钥路径 */
        public String privateKeyPath = "";
        /** 私钥 passphrase */
        public String keyPassphrase = "";
        /** 远程上传目录 */
        public String remoteDir = "/opt/app/";
        /** 上传后执行的远程命令（可选） */
        public String remoteCommand = "";
        /** 是否启用自动上传 */
        public boolean autoUpload = true;

        public ServerConfig() {
            this.id = String.valueOf(System.currentTimeMillis());
        }

        public ServerConfig(String id) {
            this.id = id;
        }

        /** 深拷贝 */
        public ServerConfig copy() {
            ServerConfig c = new ServerConfig(this.id);
            c.name = this.name;
            c.host = this.host;
            c.port = this.port;
            c.user = this.user;
            c.password = this.password;
            c.privateKeyPath = this.privateKeyPath;
            c.keyPassphrase = this.keyPassphrase;
            c.remoteDir = this.remoteDir;
            c.remoteCommand = this.remoteCommand;
            c.autoUpload = this.autoUpload;
            return c;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ServerConfig)) return false;
            return Objects.equals(id, ((ServerConfig) o).id);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id);
        }

        public String getDisplayLabel() {
            if (name != null && !name.isEmpty()) return name;
            return user + "@" + host + ":" + port;
        }
    }

    // ========== 兼容旧配置（从单配置迁移） ==========

    // 旧字段，用于序列化兼容
    @Deprecated public String sshHost = "";
    @Deprecated public int sshPort = 22;
    @Deprecated public String sshUser = "root";
    @Deprecated public String sshPassword = "";
    @Deprecated public String sshPrivateKeyPath = "";
    @Deprecated public String sshKeyPassphrase = "";
    @Deprecated public String remoteDir = "/opt/app/";
    @Deprecated public String remoteCommand = "";
    @Deprecated public boolean enabled = true;

    /**
     * 从旧单配置迁移到新列表配置。
     */
    public void migrateIfNeeded() {
        if (servers.isEmpty() && !sshHost.isEmpty()) {
            ServerConfig c = new ServerConfig();
            c.name = "默认服务器";
            c.host = sshHost;
            c.port = sshPort;
            c.user = sshUser;
            c.password = sshPassword;
            c.privateKeyPath = sshPrivateKeyPath;
            c.keyPassphrase = sshKeyPassphrase;
            c.remoteDir = remoteDir;
            c.remoteCommand = remoteCommand;
            c.autoUpload = enabled;
            servers.add(c);

            // 清空旧字段
            sshHost = "";
            sshPort = 22;
            sshUser = "root";
            sshPassword = "";
            sshPrivateKeyPath = "";
            sshKeyPassphrase = "";
            remoteDir = "/opt/app/";
            remoteCommand = "";
            enabled = true;
        }
    }

    // ========== Singleton ==========

    @Nullable
    @Override
    public MavenDeploySettings getState() {
        return this;
    }

    @Override
    public void loadState(@NotNull MavenDeploySettings state) {
        XmlSerializerUtil.copyBean(state, this);
        migrateIfNeeded();
    }

    public static MavenDeploySettings getInstance(@NotNull Project project) {
        return project.getService(MavenDeploySettings.class);
    }
}
