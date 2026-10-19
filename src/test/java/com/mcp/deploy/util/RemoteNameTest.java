package com.mcp.deploy.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 远端文件名自定义 / 重命名的单元测试。
 */
class RemoteNameTest {

    @Test
    void emptyTemplateKeepsLocalFileName() {
        assertEquals("demo-1.0.0.jar", RemoteName.resolve("", "demo-1.0.0.jar"));
        assertEquals("demo-1.0.0.jar", RemoteName.resolve("   ", "demo-1.0.0.jar"));
        assertEquals("demo-1.0.0.jar", RemoteName.resolve(null, "demo-1.0.0.jar"));
    }

    @Test
    void fixedNameOverridesLocalFileName() {
        assertEquals("app.jar", RemoteName.resolve("app.jar", "demo-1.0.0.jar"));
        assertEquals("server.jar", RemoteName.resolve("server.jar", "demo-1.0.0.jar"));
    }

    @Test
    void placeholdersAreExpanded() {
        assertEquals("demo-1.0.0.jar", RemoteName.resolve("${jar}", "demo-1.0.0.jar"));
        assertEquals("demo.jar", RemoteName.resolve("${name}.jar", "demo-1.0.0.jar"));
        assertEquals("1.0.0", RemoteName.resolve("${version}", "demo-1.0.0.jar"));
        assertEquals("demo-1.0.0.jar", RemoteName.resolve("${name}-${version}.jar", "demo-1.0.0.jar"));
        assertEquals("app-v1.0.0.jar", RemoteName.resolve("app-v${version}.jar", "demo-1.0.0.jar"));
    }

    @Test
    void versionSuffixHandling() {
        assertEquals("1.0.0", RemoteName.versionOf("demo-1.0.0"));
        assertEquals("demo", RemoteName.artifactName("demo-1.0.0"));
        assertEquals("2.0.0-SNAPSHOT", RemoteName.versionOf("my-app-2.0.0-SNAPSHOT"));
        assertEquals("my-app", RemoteName.artifactName("my-app-2.0.0-SNAPSHOT"));
        assertEquals("", RemoteName.versionOf("app"));
        assertEquals("app", RemoteName.artifactName("app"));
        assertEquals("demo-1.0.0", RemoteName.localBaseName("demo-1.0.0.jar"));
    }

    @Test
    void invalidTemplatesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> RemoteName.resolve("../app.jar", "demo-1.0.0.jar"));
        assertThrows(IllegalArgumentException.class, () -> RemoteName.resolve("sub/app.jar", "demo-1.0.0.jar"));
        assertThrows(IllegalArgumentException.class, () -> RemoteName.resolve("${unknown}.jar", "demo-1.0.0.jar"));
        assertThrows(IllegalArgumentException.class, () -> RemoteName.resolve(".jar", "demo-1.0.0.jar"));
    }

    @Test
    void checkTemplateAcceptsPlaceholdersAndEmpty() {
        assertNull(RemoteName.checkTemplate(""));
        assertNull(RemoteName.checkTemplate(null));
        assertNull(RemoteName.checkTemplate("app.jar"));
        assertNull(RemoteName.checkTemplate("${name}.jar"));
        assertNull(RemoteName.checkTemplate("${name}-${version}.jar"));
        assertNull(RemoteName.checkFileName("app.jar"));
    }

    @Test
    void checkTemplateRejectsPathSeparators() {
        assertEquals("文件名不能包含路径分隔符", RemoteName.checkTemplate("sub/app.jar"));
        assertEquals("存在未识别的占位符", RemoteName.checkTemplate("${foo}.jar"));
    }
}
