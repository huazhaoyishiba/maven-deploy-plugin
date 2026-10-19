package com.mcp.deploy.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maven 目标（goal）解析工具。
 *
 * <p>纯 Java 实现，不依赖 IntelliJ 平台，便于单元测试。用于判断一次 Maven 运行
 * 是否属于「打包」，从而决定要不要自动上传 jar。</p>
 */
public final class MavenGoalParser {

    /**
     * 会产出 jar 的打包目标。
     * clean / compile / test / validate 等目标不会触发上传。
     */
    private static final Set<String> PACKAGING_GOALS = Set.of("package", "install", "deploy");

    /**
     * IDEA 自动生成的 Maven 运行配置名形如 {@code module-name [clean, package]}，
     * 取出最后一个方括号里的内容作为目标串。
     */
    private static final Pattern BRACKETED = Pattern.compile("\\[([^\\[\\]]*)]\\s*$");

    private static final Pattern TOKEN_SPLIT = Pattern.compile("[\\s,]+");

    /**
     * 合法目标名：{@code package}、{@code clean}、{@code dependency:tree}、
     * {@code org.apache.maven.plugins:maven-jar-plugin:jar} 等。
     */
    private static final Pattern GOAL_TOKEN = Pattern.compile("[A-Za-z0-9_.\\-]+(?::[A-Za-z0-9_.\\-]+)*");

    /** 名称里直接出现打包关键字（无法解析出目标列表时的兜底判断）。 */
    private static final Pattern PACKAGING_KEYWORD =
        Pattern.compile("(?i)(?:^|[^a-z0-9])(package|install|deploy)(?:$|[^a-z0-9])");

    private MavenGoalParser() {
    }

    /**
     * 从运行配置名称中解析目标列表。
     *
     * @param configName 运行配置名，例如 {@code demo [clean, package]}、{@code demo [package]}
     */
    public static List<String> goalsFromName(String configName) {
        if (configName == null) return List.of();
        String body = configName.trim();
        if (body.isEmpty()) return List.of();

        Matcher bracketed = BRACKETED.matcher(body);
        if (bracketed.find()) {
            body = bracketed.group(1);
        }
        return normalizeGoals(TOKEN_SPLIT.split(body));
    }

    /**
     * 归一化 Maven 参数列表：去掉 {@code -Dxxx}、{@code -Pxxx}、{@code -pl} 等参数，
     * 只保留真正的 goal。
     */
    public static List<String> normalizeGoals(String... rawArgs) {
        if (rawArgs == null) return List.of();
        List<String> goals = new ArrayList<>();
        for (String raw : rawArgs) {
            if (raw == null) continue;
            // 一个参数里可能带多个 goal，例如 "clean package"
            for (String token : TOKEN_SPLIT.split(raw.trim())) {
                String t = token.trim();
                if (t.isEmpty() || t.startsWith("-")) continue;
                if (!GOAL_TOKEN.matcher(t).matches()) continue;
                goals.add(t);
            }
        }
        return goals;
    }

    /** 归一化 Maven 参数集合（保持顺序）。 */
    public static List<String> normalizeGoals(Collection<String> rawArgs) {
        if (rawArgs == null) return List.of();
        return normalizeGoals(rawArgs.toArray(new String[0]));
    }

    /** 是否是会产出 jar 的打包目标（支持 {@code plugin:goal} 写法）。 */
    public static boolean isPackagingGoal(String goal) {
        return PACKAGING_GOALS.contains(normalizeGoalName(goal));
    }

    /** 目标列表里是否包含打包目标。 */
    public static boolean hasPackagingGoal(Collection<String> goals) {
        return packagingGoal(goals) != null;
    }

    /** 返回命中的打包目标名，没有则返回 {@code null}（用于日志）。 */
    public static String packagingGoal(Collection<String> goals) {
        if (goals == null) return null;
        for (String goal : goals) {
            String normalized = normalizeGoalName(goal);
            if (PACKAGING_GOALS.contains(normalized)) return normalized;
        }
        return null;
    }

    /**
     * 兜底判断：运行配置名里是否出现了打包关键字。
     * 只在拿不到真实目标列表时使用。
     */
    public static boolean nameSuggestsPackaging(String configName) {
        if (configName == null || configName.isBlank()) return false;
        List<String> goals = goalsFromName(configName);
        if (!goals.isEmpty()) {
            // 名称里能解析出目标，就以目标为准，避免「deploy-tool [clean]」这类名字误判
            return hasPackagingGoal(goals);
        }
        return PACKAGING_KEYWORD.matcher(configName).find();
    }

    /** {@code org.apache.maven.plugins:maven-jar-plugin:jar} → {@code jar} */
    private static String normalizeGoalName(String goal) {
        if (goal == null) return "";
        String g = goal.trim().toLowerCase(Locale.ROOT);
        int idx = g.lastIndexOf(':');
        if (idx >= 0 && idx < g.length() - 1) {
            g = g.substring(idx + 1);
        }
        return g;
    }
}
