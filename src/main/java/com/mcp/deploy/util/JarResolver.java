package com.mcp.deploy.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * 本地 jar 定位工具。
 *
 * <p>纯 Java 实现，不依赖 IntelliJ 平台，便于单元测试。所有 {@link Stream} 都用
 * try-with-resources 关闭，避免在 Windows 上因为目录句柄未释放导致
 * {@code Files.list} / {@code Files.walk} 结果不稳定（旧实现就是这样偶发找不到 jar 的）。</p>
 */
public final class JarResolver {

    /** 不参与部署的 jar 后缀（源码包、javadoc、测试包、thin jar）。 */
    private static final List<String> EXCLUDED_SUFFIXES = List.of(
        "-sources.jar",
        "-javadoc.jar",
        "-tests.jar",
        "-test-sources.jar",
        "-plain.jar"
    );

    /** 打包插件留下的原始包。 */
    private static final String ORIGINAL_PREFIX = "original-";

    /** 新构建的 jar 排在前面。 */
    private static final Comparator<Candidate> NEWEST_FIRST =
        Comparator.comparingLong(Candidate::lastModified).reversed()
            .thenComparing(Comparator.comparingLong(Candidate::size).reversed())
            .thenComparing(c -> c.path().getFileName().toString());

    private JarResolver() {
    }

    /** 一个候选 jar。 */
    public record Candidate(Path path, long size, long lastModified) {
        public String describe() {
            return path + " (" + size + " B, " + java.time.Instant.ofEpochMilli(lastModified) + ")";
        }
    }

    /** 是否是可用于部署的 jar。 */
    public static boolean isDeliverableJar(String fileName) {
        if (fileName == null) return false;
        String name = fileName.toLowerCase(Locale.ROOT);
        if (!name.endsWith(".jar")) return false;
        for (String suffix : EXCLUDED_SUFFIXES) {
            if (name.endsWith(suffix)) return false;
        }
        return !name.startsWith(ORIGINAL_PREFIX);
    }

    /** 列出目录下所有候选 jar，最新的排在最前面。 */
    public static List<Candidate> candidates(Path dir) throws IOException {
        if (dir == null || !Files.isDirectory(dir)) return List.of();
        List<Candidate> result = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                if (!Files.isRegularFile(p)) continue;
                if (!isDeliverableJar(p.getFileName().toString())) continue;
                result.add(toCandidate(p));
            }
        }
        result.sort(NEWEST_FIRST);
        return result;
    }

    /**
     * 定位本次需要上传的 jar：固定规则 —— 取「最新构建」的可部署 jar。
     *
     * <p>搜索顺序：本次 Maven 运行的模块目录 {@code target} → 项目根 {@code target} →
     * 项目内任意模块的 {@code target}（取最新）。</p>
     *
     * @param projectBase        项目根目录（用于多模块兜底搜索）
     * @param preferredModuleDir 本次 Maven 运行的工作目录（通常就是被打包的模块目录），可为 null
     * @return 命中的 jar，找不到返回 {@code null}
     */
    public static Path resolve(Path projectBase, Path preferredModuleDir) throws IOException {
        List<Path> dirs = new ArrayList<>();
        if (preferredModuleDir != null) dirs.add(preferredModuleDir);
        if (projectBase != null && !dirs.contains(projectBase)) dirs.add(projectBase);

        for (Path dir : dirs) {
            Path hit = newestInTarget(dir);
            if (hit != null) return hit;
        }

        // 多模块兜底：扫描任意 target 目录，取最新构建的 jar
        if (projectBase != null) {
            Path hit = newestJarInTargetDirs(projectBase, 6);
            if (hit != null) return hit;
        }
        return null;
    }

    /** 某个模块目录的 {@code target} 下最新的可部署 jar。 */
    public static Path newestInTarget(Path moduleDir) throws IOException {
        if (moduleDir == null) return null;
        Path targetDir = moduleDir.resolve("target");
        if (!Files.isDirectory(targetDir)) return null;
        List<Candidate> list = candidates(targetDir);
        return list.isEmpty() ? null : list.get(0).path();
    }

    /** 在项目内任意 {@code target} 目录里找最新的可部署 jar。 */
    public static Path newestJarInTargetDirs(Path root, int maxDepth) throws IOException {
        List<Path> found = listJarsInTargetDirs(root, maxDepth, 1);
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * 列出项目内任意 {@code target} 目录里的可部署 jar（最新的在前）。
     * 用于「找不到 jar」时把实际存在的候选告诉用户。
     */
    public static List<Path> listJarsInTargetDirs(Path root, int maxDepth, int limit) throws IOException {
        if (root == null || !Files.isDirectory(root)) return List.of();
        List<Candidate> found = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root, Math.max(1, maxDepth))) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                if (!Files.isRegularFile(p)) continue;
                Path parent = p.getParent();
                if (parent == null || !"target".equalsIgnoreCase(String.valueOf(parent.getFileName()))) continue;
                if (!isDeliverableJar(p.getFileName().toString())) continue;
                found.add(toCandidate(p));
            }
        }
        found.sort(NEWEST_FIRST);
        return found.stream()
            .limit(Math.max(1, limit))
            .map(Candidate::path)
            .toList();
    }

    /** 与 {@link #resolve} 相同的搜索范围，但只返回候选 jar，便于提示用户。 */
    public static List<Path> listCandidatesFor(Path projectBase, Path preferredModuleDir, int limit)
        throws IOException {
        List<Path> result = new ArrayList<>();
        collectCandidates(preferredModuleDir, result);
        collectCandidates(projectBase, result);
        if (projectBase != null) {
            // 兜底：扫描各模块的 target
            for (Path p : listJarsInTargetDirs(projectBase, 6, Math.max(1, limit))) {
                addIfAbsent(result, p);
            }
        }
        return result.size() <= limit ? result : result.subList(0, limit);
    }

    private static void collectCandidates(Path moduleDir, List<Path> result) throws IOException {
        if (moduleDir == null) return;
        for (Candidate c : candidates(moduleDir.resolve("target"))) {
            addIfAbsent(result, c.path());
        }
    }

    /** 按真实路径去重：模块目录和项目根经常是同一个目录，否则会重复列出。 */
    private static void addIfAbsent(List<Path> result, Path candidate) {
        Path normalized = candidate.toAbsolutePath().normalize();
        for (Path existing : result) {
            if (existing.toAbsolutePath().normalize().equals(normalized)) return;
        }
        result.add(candidate);
    }

    private static Candidate toCandidate(Path p) {
        return new Candidate(p, sizeOf(p), lastModified(p));
    }

    private static long sizeOf(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0L;
        }
    }

    private static long lastModified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }
}
