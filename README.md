# Maven Deploy Plugin

IntelliJ IDEA 插件 — 监听 Maven 打包完成，自动通过 SSH 将 jar 上传到远程服务器。

## 功能

- 🔍 自动监听 Maven `package` / `install` / `deploy` 目标执行
- 🔐 SSH 密码认证 + 密钥认证 双模式
- 📦 自动读取 `pom.xml` 获取 jar 名称，也支持手动指定
- ⚡ 打包成功后自动 SFTP 上传到服务器指定目录
- 🔧 上传后可执行远程命令（如 `systemctl restart xxx`）
- 💬 上传进度与结果通知

## 开发环境要求

- JDK 17+
- IntelliJ IDEA 2023.3+ (Community 或 Ultimate)
- Gradle 8.7 (自动通过 wrapper 下载)

## 快速开始

### 1. 在 IDEA 中打开项目

```
File → Open → 选择 D:\mc\maven-deploy-plugin 目录
```

IDEA 会自动识别为 Gradle 项目并下载依赖。

### 2. 运行 / 调试插件

在 Gradle 工具窗口中执行：

```
Tasks → intellij → runIde
```

这会启动一个新的 IDEA 沙盒实例，其中已经加载了该插件。

### 3. 构建发布包

```
gradle buildPlugin
```

产物在 `build/distributions/` 下。

## 项目结构

```
maven-deploy-plugin/
├── build.gradle.kts              # 构建配置 (gradle-intellij-plugin)
├── settings.gradle.kts
├── gradle.properties
├── src/main/
│   ├── java/com/mcp/deploy/
│   │   ├── settings/
│   │   │   ├── MavenDeploySettings.java    # 配置持久化存储
│   │   │   └── SettingsConfigurable.java   # Settings UI 面板
│   │   ├── listener/
│   │   │   └── MavenDeployExecutionListener.java  # Maven 事件监听
│   │   ├── ssh/
│   │   │   └── SshUploader.java            # SSH/SFTP 上传
│   │   └── notify/
│   │       └── DeployNotification.java     # 通知弹窗
│   └── resources/META-INF/
│       └── plugin.xml                      # 插件描述与注册
└── gradle/
    └── wrapper/                             # Gradle Wrapper
```

## 使用方式

1. 安装插件后，打开 `File → Settings → Tools → Maven Deploy`
2. 配置 SSH 连接信息：
   - 主机地址 / 端口 / 用户名
   - 密码 或 私钥路径（二选一，都填则优先私钥）
3. 配置上传目录和 Jar 名称规则
4. 勾选"启用"
5. 正常执行 Maven 打包，成功后自动上传

## 配置项说明

| 配置项 | 说明 | 默认值 |
|--------|------|--------|
| SSH 主机 | 服务器地址 | - |
| SSH 端口 | SSH 端口 | 22 |
| SSH 用户 | 登录用户名 | root |
| 密码 / 私钥 | 认证方式 | - |
| 远程目录 | 上传目标路径 | /opt/app/ |
| Jar 名称模式 | auto=读取 pom.xml, manual=手动指定 | auto |
| 连接超时 | SSH 连接超时（秒） | 10 |
| 上传后执行 | 远程命令（如重启） | - |

## 工作原理

```
Maven 打包执行
     ↓
ExecutionListener.processTerminated()
     ↓ 判断: 是 Maven + package/install/deploy?
     ↓ exitCode == 0?
     ↓
搜索 target/*.jar
     ↓
SSH 连接 → SFTP 上传 → 可选远程命令
     ↓
弹出通知（成功/失败）
```

## 技术栈

- IntelliJ Platform SDK
- JSch (SSH/SFTP)
- gradle-intellij-plugin
