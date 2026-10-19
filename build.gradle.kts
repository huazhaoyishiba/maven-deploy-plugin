plugins {
    id("java")
    id("org.jetbrains.intellij") version "1.17.4"
}

group = "com.mcp.deploy"
version = "1.1.0"

repositories {
    mavenCentral()
}

dependencies {
    // JSch（mwiede 维护版）：支持 ed25519 / rsa-sha2-256/512，适配新版 OpenSSH 服务端
    implementation("com.github.mwiede:jsch:0.2.26")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}

intellij {
    version.set("2023.3.8")
    type.set("IC") // Community Edition
    plugins.set(listOf(
        "org.jetbrains.idea.maven" // Maven 支持
    ))
    // 不下载 IDE 源码包：编译与测试都用不到，网络差时会卡上很久
    downloadSources.set(false)
}

tasks {
    buildSearchableOptions {
        enabled = false
    }
    test {
        useJUnitPlatform()
        testLogging {
            events("passed", "failed", "skipped")
        }
    }
    patchPluginXml {
        sinceBuild.set("222")
        untilBuild.set("242.*")
        changeNotes.set("""
        <h3>v1.1.0</h3>
        <ul>
          <li>修复：多目标运行（如 clean package）不再漏传，只有在 package/install/deploy 成功后才自动上传</li>
          <li>修复：多模块/多 jar 项目按“本次构建目录 + 最新产物”精确选择 jar</li>
          <li>修复：~ 与相对路径的远程目录会被正确解析，不再上传到错误位置</li>
          <li>修复：大文件上传时不再被 10 秒 socket 读超时打断</li>
          <li>改进：上传失败自动重试、临时文件校验 + 原子替换，避免半个 jar 覆盖线上</li>
          <li>改进：错误原因可读化（认证失败/算法协商失败/权限不足/超时等）</li>
          <li>改进：JSch 升级到 mwiede 0.2.26，支持 ed25519 与 rsa-sha2</li>
          <li>新增：可自定义上传后的远端文件名（固定名或 ${jar}/${name}/${version} 占位符）</li>
          <li>新增：开始上传时先弹通知（jar/大小/目标服务器），结束后收起并汇总结果</li>
          <li>变更：去掉「手动指定 jar 名称」，固定上传 target 下最新构建的可部署 jar</li>
          <li>改进：找不到 jar 时的提示会列出实际存在的候选 jar，并区分「远端文件名」与「选哪个 jar」</li>
          <li>新增：插件描述/面板提示写明触发范围（Terminal 里 mvn 不触发）</li>
        </ul>
        <h3>v1.0.0</h3>
        <ul>
          <li>监听 Maven 打包完成事件</li>
          <li>支持 SSH 密码 + 密钥认证</li>
          <li>自动上传 jar 到远程服务器</li>
          <li>支持自动读取 pom.xml 或手动配置 jar 名称</li>
        </ul>
        """.trimIndent())
    }

    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN") ?: "")
        privateKey.set(System.getenv("PRIVATE_KEY") ?: "")
        password.set(System.getenv("PRIVATE_KEY_PASSWORD") ?: "")
    }

    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN") ?: "")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
