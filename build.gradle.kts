plugins {
    id("java")
    id("org.jetbrains.intellij") version "1.17.4"
}

group = "com.mcp.deploy"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // JSch for SSH/SCP (密码认证)
    implementation("com.jcraft:jsch:0.1.55")

    // Jackson for JSON (可选，用于配置文件解析)
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
}

intellij {
    version.set("2023.3.8")
    type.set("IC") // Community Edition
    plugins.set(listOf(
        "org.jetbrains.idea.maven" // Maven 支持
    ))
}

tasks {
    buildSearchableOptions {
        enabled = false
    }
    patchPluginXml {
        sinceBuild.set("203")
        untilBuild.set("242.*")
        changeNotes.set("""
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
