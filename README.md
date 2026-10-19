# Maven Deploy Plugin

IntelliJ IDEA 插件 — 监听 Maven **打包**完成，自动通过 SSH 将 jar 上传到远程服务器。

## 功能

- 🔍 **只在 Maven `package` / `install` / `deploy` 成功后才自动上传**（`clean`、`compile`、`test` 等目标不会触发）
- 🔐 SSH 密码认证 + 密钥认证（支持 ed25519 / rsa-sha2，适配新版 OpenSSH 服务端）
- 📦 多模块项目按「本次构建的模块目录 + 最新产物」精确选择 jar
- ⚡ 打包成功后并发 SFTP 上传到多台服务器，带进度与结果通知
- 🔧 上传后可执行远程命令（如 `systemctl restart xxx`）
- 💬 上传**开始**时就弹通知（jar 名/大小/目标服务器），结束后收起并给结果，不会“静默一阵子”
- ♻️ 上传失败自动重试；先传临时文件、校验大小后再原子替换，避免半个 jar 覆盖线上

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

### 4. 跑单元测试

```
gradle test
```

`MavenGoalParserTest` / `JarResolverTest` / `RemotePathTest` 覆盖了「哪个目标算打包」「多模块找哪个 jar」
「`~` 与相对路径的远端目录如何解析」三块最容易出问题的逻辑。

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
│   │   │   ├── MavenDeployToolWindowFactory.java
│   │   │   └── MavenDeployToolWindowPanel.java  # 右侧面板（列表 / 编辑表单）
│   │   ├── listener/
│   │   │   └── MavenDeployExecutionListener.java  # Maven 事件监听（判断是否打包）
│   │   ├── ssh/
│   │   │   └── SshUploader.java            # SSH/SFTP 上传（重试 / 校验 / 原子替换）
│   │   ├── notify/
│   │   │   └── DeployNotification.java     # 通知弹窗
│   │   └── util/
│   │       ├── MavenGoalParser.java        # Maven goal 解析（是否打包）
│   │       ├── JarResolver.java            # 本地 jar 定位
│   │       └── RemotePath.java             # 远端路径归一化
│   └── resources/META-INF/
│       └── plugin.xml                      # 插件描述与注册
├── src/test/java/com/mcp/deploy/util/      # 纯 Java 单元测试
└── gradle/wrapper/                         # Gradle Wrapper
```

## 使用方式

1. 安装插件后，打开右侧边栏 `Maven Deploy` 工具窗口
2. 点击「新建」配置 SSH 连接信息：
   - 主机地址 / 端口 / 用户名
   - 密码 或 私钥路径（二选一，都填则优先私钥）
3. 配置上传目录、**上传后的文件名**，勾选「自动」列表示该服务器参与自动部署
4. 顶部勾选「启用插件」
5. 正常执行 Maven 打包（`package` / `install` / `deploy`），成功后自动上传

> 「上传选中」按钮是**手动**触发一次上传，与自动上传互不影响。

### 上传哪个 jar：固定取最新构建的

没有“选哪个 jar”的配置项，规则写死为**取最新构建的可部署 jar**：

- 搜索顺序：本次 Maven 运行的模块 `target` → 项目根 `target` → 项目内任意模块 `target`（取最新）
- 自动排除：`*-sources.jar`、`*-javadoc.jar`、`*-tests.jar`、`original-*.jar`、`*.jar.original`、`*-plain.jar`
- 多个候选时按**修改时间**取最新（Spring Boot 的 `repackage` 会把 thin jar 改名成 `.jar.original`，正好被排除）

所以 `document-chunker-1.0.0.jar` 这种名字不用配置，打包出来就直接传它。
如果只想改**上传后**的名字，用下面服务器配置里的「远端文件名」。

### 自定义远端文件名（重命名）

编辑服务器配置 →「上传配置」→「远端文件名」：

| 填法 | 本地 `demo-1.0.0.jar` 上传后 | 说明 |
|------|------------------------------|------|
| 留空 | `demo-1.0.0.jar` | 默认：沿用本地文件名 |
| `app.jar` | `app.jar` | 固定名，适合配合 `systemctl restart app` |
| `${jar}` | `demo-1.0.0.jar` | 本地完整文件名 |
| `${name}.jar` | `demo.jar` | 去掉版本号的名字 |
| `${name}-${version}.jar` | `demo-1.0.0.jar` | 名字 + 版本号 |
| `app-${version}.jar` | `app-1.0.0.jar` | 自定义前缀 + 版本号 |

文件名不能包含路径分隔符（要换目录就改「远程目录」），保存时就会校验，填错会直接在表单里报红。
上传过程仍然是「临时文件 → 校验大小 → 原子替换」，重命名不会破坏这个流程。

### 触发范围（重要）

插件监听的是 **IDEA 内的 Maven 运行配置**，也就是 Maven 工具窗口、`Run/Debug Configurations`、
`Execute Maven Goal` 对话框背后创建并执行的那类运行配置：

| 打包方式 | 自动上传 |
|----------|----------|
| Maven 工具窗口双击 `package` / `install` / `deploy` | ✅ 触发 |
| 右键模块 → `Run Maven Build`，或用已有 Maven 运行配置 | ✅ 触发 |
| Terminal / 命令行里直接 `mvn package` | ❌ 不触发（没有运行配置事件） |

在命令行打包、又需要部署时，用面板上的「上传选中」手动上传一次。

## 配置项说明

| 配置项 | 说明 | 默认值 |
|--------|------|--------|
| SSH 主机 | 服务器地址 | - |
| SSH 端口 | SSH 端口 | 22 |
| SSH 用户 | 登录用户名 | root |
| 密码 / 私钥 | 认证方式（私钥支持 OpenSSH/PEM，含 ed25519） | - |
| 远程目录 | 上传目标路径，支持 `~/app`、`app`（相对 home） | /opt/app/ |
| 远端文件名 | 上传后的文件名；留空=本地 jar 名，支持 `${jar}` `${name}` `${version}` | 空（用本地名） |
| 连接超时 | SSH 连接/握手的超时（秒）；文件传输本身不设读超时 | 10 |
| 上传后执行 | 远程命令（如重启） | - |
| 自动上传 | 每台服务器独立开关 | 开启 |

## 工作原理

```
Maven 打包执行（package / install / deploy）
     ↓
ExecutionListener.processTerminated()
     ↓ 从 Maven 运行配置读取真实 goals（读不到则解析配置名）
     ↓ 目标里没有 package/install/deploy → 直接跳过，不上传
     ↓ exitCode == 0?
     ↓
按「本次构建的模块目录 → 项目根 → 任意 target」顺序找最新 jar
     ↓
SSH 连接（超时只作用于连接阶段）
     ↓
远端目录归一化：~ 展开为 home，相对路径按 home 解析
     ↓
按「远端文件名」模板生成文件名（留空=本地名，支持 ${jar}/${name}/${version}）
     ↓
上传到 xxx.jar.maven-deploy.tmp → 校验大小 → mv -f 原子替换
     ↓
可选远程命令
     ↓
弹通知：开始上传（含 jar 名/大小/目标服务器）→ 结束收起 → 成功/失败结果
     ↓
失败自动重试（最多 3 次）
```

## 排查：上传不生效 / 上传失败

先看日志：`Help → Show Log in Explorer` 打开 `idea.log`，搜索 `MavenDeployExecutionListener` 或 `SshUploader`。

| 现象 | 日志线索 | 原因与处理 |
|------|----------|------------|
| 打包成功但完全没上传 | `跳过自动上传：本次 Maven 运行不是打包` | 本次运行的目标里没有 `package/install/deploy`（例如只跑了 `clean`）；或运行配置是「Application/Spring Boot」等其他类型，插件只监听 Maven 运行配置 |
| 打包成功但没上传 | `已打包成功，但没有找到可上传的 jar` + `选取规则: target 下最新构建的可部署 jar` | `target` 下没有可部署 jar（被 `clean` 删掉、只跑了 `compile`），或只留下 `*-sources` / `original-*`；提示里会列出**实际找到的 jar**，先跑一次 `package` |
| 上传失败：认证失败 | `Auth fail` | 用户名/密码或私钥错误；私钥填了但服务端只接受密码时，密码需一并填写 |
| 上传失败：算法协商 | `Algorithm negotiation fail` | 服务端禁用旧算法（OpenSSH 8.8+ 默认禁用 `ssh-rsa`）；v1.1.0 起换用 mwiede JSch，支持 `rsa-sha2-256/512` 与 ed25519 |
| 上传失败：私钥无法识别 | `invalid privatekey` | 私钥格式不受支持或私钥有 passphrase 未填；建议使用 OpenSSH 格式的 `id_ed25519` / `id_rsa` |
| 上传失败：目录不存在 / 权限不足 | `No such file` / `Permission denied` | 检查远程目录属主与权限；`~/app` 与 `app` 会按登录用户 home 解析 |
| 上传到一半失败 / 线上 jar 损坏 | `上传不完整` / `传输结束` 之后无 `上传完成` | v1.1.0 起先写临时文件再 `mv -f` 原子替换，没完成不会覆盖旧 jar；重试 3 次仍失败会弹错误通知 |
| 上传失败：远端文件名非法 | `远端文件名配置有误` / `文件名不能包含路径分隔符` | 「远端文件名」填了带 `/` 的名字或未识别的占位符（如 `${nmae}`）；保存表单时也会拦截 |
| 大 jar 上传超时 | `SocketTimeoutException: Read timed out` | 旧版本把 SSH 读超时设成 10 秒，传输慢时被打断；v1.1.0 起传输阶段不设读超时 |
| 多模块上传了错误的 jar | 日志里的 `准备上传 jar=...` | 旧版本按「target 下体积最大的 jar」猜，容易选到 `*-shaded`；现在按最新构建时间选，并优先本次构建模块目录 |

如果日志里没有任何 `MavenDeployExecutionListener` 记录，说明事件根本没到插件：确认插件已启用（面板顶部「启用插件」）、IDEA 版本在支持范围内，并用 Maven 工具窗口/运行配置执行打包（**在 Terminal 里敲 `mvn` 插件感知不到，见上面的「触发范围」**）。

## 技术栈

- IntelliJ Platform SDK
- JSch（mwiede 维护版 0.2.26）：SSH/SFTP，支持 ed25519 / rsa-sha2
- gradle-intellij-plugin
