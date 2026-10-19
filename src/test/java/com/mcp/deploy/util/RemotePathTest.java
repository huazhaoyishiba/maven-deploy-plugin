package com.mcp.deploy.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 远端路径单元测试：覆盖 {@code ~/app}、相对路径、重复斜杠等会被拼错位置的写法。
 */
class RemotePathTest {

    @Test
    void normalizeDirCleansSlashesAndSpaces() {
        assertEquals("/opt/app", RemotePath.normalizeDir("  /opt/app/  "));
        assertEquals("/opt/app", RemotePath.normalizeDir("/opt//app///"));
        assertEquals("/opt/app", RemotePath.normalizeDir("\\opt\\app\\"));
        assertEquals("/", RemotePath.normalizeDir(""));
        assertEquals("/", RemotePath.normalizeDir(null));
        assertEquals("/", RemotePath.normalizeDir("/"));
    }

    @Test
    void expandHomeHandlesTildeAndRelativePath() {
        assertEquals("/root/app", RemotePath.expandHome("~/app", "/root"));
        assertEquals("/root", RemotePath.expandHome("~", "/root"));
        assertEquals("/home/u/app", RemotePath.expandHome("/home/u/app", "/home/u/"));
        // 相对路径以 home 为基准，而不是进程当前目录
        assertEquals("/home/u/app", RemotePath.expandHome("app", "/home/u"));
        assertEquals("/app", RemotePath.expandHome("app", "/"));
    }

    @Test
    void joinProducesSingleSlash() {
        assertEquals("/opt/app/app.jar", RemotePath.join("/opt/app/", "app.jar"));
        assertEquals("/opt/app/app.jar", RemotePath.join("/opt/app", "app.jar"));
        assertEquals("/app.jar", RemotePath.join("/", "app.jar"));
        assertEquals("/opt/app/app.jar", RemotePath.join(" /opt/app// ", "app.jar"));
    }

    @Test
    void fileNameExtractsLastSegment() {
        assertEquals("app.jar", RemotePath.fileName("/opt/app/app.jar"));
        assertEquals("app.jar", RemotePath.fileName("C:\\opt\\app\\app.jar"));
        assertEquals("app.jar", RemotePath.fileName("app.jar"));
        assertEquals("", RemotePath.fileName(null));
    }
}
