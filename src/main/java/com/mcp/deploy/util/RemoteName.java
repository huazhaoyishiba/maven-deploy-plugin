package com.mcp.deploy.util;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 远端 jar 文件名解析工具。
 *
 * <p>纯 Java 实现，不依赖 IntelliJ 平台，便于单元测试。用于把本地构建产物
 * {@code demo-1.0.0.jar} 按用户配置的目标名上传，例如固定成 {@code app.jar}，
 * 以便配合 {@code systemctl restart app} 之类的远程命令。</p>
 *
 * <p>支持的占位符：</p>
 * <ul>
 *   <li>{@code ${jar}} → 本地完整文件名，如 {@code demo-1.0.0.jar}</li>
 *   <li>{@code ${name}} → 去掉版本号的名字，如 {@code demo}</li>
 *   <li>{@code ${version}} → 版本号，如 {@code 1.0.0}；没有版本号时为空串</li>
 * </ul>
 */
public final class RemoteName {

    /** 末尾的 {@code -1.0.0} / {@code -2.0.0-SNAPSHOT} 这类版本号。 */
    private static final Pattern VERSION_SUFFIX = Pattern.compile("-(\\d[0-9A-Za-z._\\-]*)$");

    private RemoteName() {
    }

    /**
     * 计算远端文件名。
     *
     * @param template      用户配置的模板，为空表示沿用本地文件名
     * @param localFileName 本地 jar 文件名（不含目录），如 {@code demo-1.0.0.jar}
     * @return 远端文件名
     * @throws IllegalArgumentException 模板解析结果非法（空、带路径分隔符、残留占位符等）
     */
    public static String resolve(String template, String localFileName) {
        String local = localFileName == null ? "" : localFileName.trim();
        if (template == null || template.trim().isEmpty()) {
            return local;
        }

        String base = localBaseName(local);
        String version = versionOf(base);
        String artifact = artifactName(base);

        String resolved = template.trim()
            .replace("${jar}", local)
            .replace("${name}", artifact)
            .replace("${version}", version)
            .trim();

        String error = checkFileName(resolved);
        if (error != null) {
            throw new IllegalArgumentException(error + ": " + resolved);
        }
        return resolved;
    }

    /**
     * 校验文件名是否可用（也用于保存配置时校验模板）。
     *
     * @return 错误提示；合法时返回 {@code null}
     */
    public static String checkFileName(String name) {
        if (name == null || name.trim().isEmpty()) return "文件名不能为空";
        String n = name.trim();
        if (n.contains("/") || n.contains("\\")) return "文件名不能包含路径分隔符";
        if (".".equals(n) || "..".equals(n)) return "文件名非法";
        if (n.startsWith(".")) return "文件名不能以 . 开头";
        if (n.contains("${") || n.contains("}")) return "存在未识别的占位符";
        return null;
    }

    /**
     * 校验模板（保存配置时用）。空模板合法，表示沿用本地文件名。
     *
     * @return 错误提示；合法时返回 {@code null}
     */
    public static String checkTemplate(String template) {
        if (template == null || template.trim().isEmpty()) return null;
        String probe = template.trim()
            .replace("${jar}", "app-1.0.0.jar")
            .replace("${name}", "app")
            .replace("${version}", "1.0.0");
        // 模板允许出现占位符，替换成样例值后复用文件名校验
        return checkFileName(probe);
    }

    /** 去掉 {@code .jar} 后缀。 */
    public static String localBaseName(String fileName) {
        if (fileName == null) return "";
        String name = fileName.trim();
        if (name.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            return name.substring(0, name.length() - 4);
        }
        return name;
    }

    /** 从 {@code demo-1.0.0} 中取出 {@code 1.0.0}；没有版本号返回空串。 */
    public static String versionOf(String baseName) {
        if (baseName == null) return "";
        Matcher matcher = VERSION_SUFFIX.matcher(baseName);
        return matcher.find() ? matcher.group(1) : "";
    }

    /** 从 {@code demo-1.0.0} 中取出 {@code demo}。 */
    public static String artifactName(String baseName) {
        if (baseName == null) return "";
        String base = baseName.trim();
        String version = versionOf(base);
        if (version.isEmpty()) return base;
        return base.substring(0, base.length() - version.length() - 1);
    }
}
