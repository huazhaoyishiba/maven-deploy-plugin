package com.mcp.deploy.util;

/**
 * 远端路径处理工具。
 *
 * <p>纯 Java 实现，不依赖 IntelliJ 平台，便于单元测试。旧实现直接把用户填的
 * {@code remoteDir} 拼成远端路径，遇到 {@code ~/app}、{@code app}、{@code /opt/app//}
 * 这类写法时会向错误的位置上传 / mkdir（甚至会在根目录建一个名为 {@code ~} 的目录），
 * 表现为「有时候上传不了」。</p>
 */
public final class RemotePath {

    private RemotePath() {
    }

    /** 归一化为绝对/相对均可的整洁路径：反斜杠转正斜杠、合并重复斜杠、去掉结尾斜杠。 */
    public static String normalizeDir(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "/";
        String dir = raw.trim().replace('\\', '/');
        // 合并重复斜杠，保留开头的斜杠
        dir = dir.replaceAll("/{2,}", "/");
        while (dir.length() > 1 && dir.endsWith("/")) {
            dir = dir.substring(0, dir.length() - 1);
        }
        return dir.isEmpty() ? "/" : dir;
    }

    /**
     * 展开 {@code ~} 与相对路径。
     *
     * @param rawDir     用户配置的远程目录
     * @param remoteHome 远端用户 home 目录（{@code sftp.getHome()}）
     */
    public static String expandHome(String rawDir, String remoteHome) {
        String home = normalizeDir(remoteHome);
        String dir = normalizeDir(rawDir);
        if ("~".equals(dir)) return home;
        if (dir.startsWith("~/")) return home + dir.substring(1);
        if (!dir.startsWith("/")) {
            // 相对路径：相对于 home，而不是相对于进程当前目录
            return home.equals("/") ? "/" + dir : home + "/" + dir;
        }
        return dir;
    }

    /** 拼接目录与文件名。 */
    public static String join(String dir, String fileName) {
        String base = normalizeDir(dir);
        String name = fileName == null ? "" : fileName.trim();
        if (base.equals("/")) return "/" + name;
        return base + "/" + name;
    }

    /** 取路径最后一段。 */
    public static String fileName(String path) {
        if (path == null) return "";
        String p = path.trim().replace('\\', '/');
        int idx = p.lastIndexOf('/');
        return idx >= 0 ? p.substring(idx + 1) : p;
    }
}
