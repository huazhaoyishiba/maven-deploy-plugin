package com.mcp.deploy.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * jar 定位单元测试：固定规则 —— 永远取「最新构建」的可部署 jar。
 */
class JarResolverTest {

    @TempDir
    Path temp;

    @Test
    void excludesSourcesJavadocTestsAndOriginalJars() throws IOException {
        Path target = Files.createDirectories(temp.resolve("target"));
        writeJar(target.resolve("demo-1.0.0.jar"), 10);
        writeJar(target.resolve("demo-1.0.0-sources.jar"), 20);
        writeJar(target.resolve("demo-1.0.0-javadoc.jar"), 30);
        writeJar(target.resolve("demo-1.0.0-tests.jar"), 40);
        writeJar(target.resolve("original-demo-1.0.0.jar"), 50);
        writeJar(target.resolve("demo-1.0.0.jar.original"), 60);

        Path jar = JarResolver.resolve(temp, temp);

        assertNotNull(jar);
        assertEquals("demo-1.0.0.jar", jar.getFileName().toString());
    }

    @Test
    void prefersNewestJarOverLargestJar() throws IOException {
        Path target = Files.createDirectories(temp.resolve("target"));
        Path oldBig = writeJar(target.resolve("old-1.0.0.jar"), 500);
        Files.setLastModifiedTime(oldBig, FileTime.fromMillis(System.currentTimeMillis() - 600_000L));
        writeJar(target.resolve("demo-1.0.0.jar"), 5);

        Path jar = JarResolver.resolve(temp, temp);

        assertNotNull(jar);
        assertEquals("demo-1.0.0.jar", jar.getFileName().toString());
    }

    @Test
    void preferredModuleDirWinsOverProjectRoot() throws IOException {
        Path moduleA = Files.createDirectories(temp.resolve("module-a/target"));
        writeJar(moduleA.resolve("module-a.jar"), 10);
        Path moduleB = Files.createDirectories(temp.resolve("module-b/target"));
        writeJar(moduleB.resolve("module-b.jar"), 20);

        Path jar = JarResolver.resolve(temp, temp.resolve("module-a"));

        assertNotNull(jar);
        assertEquals("module-a.jar", jar.getFileName().toString());
    }

    @Test
    void fallsBackToNewestJarUnderAnyTargetWhenRootHasNoTarget() throws IOException {
        Path moduleTarget = Files.createDirectories(temp.resolve("some-module/target"));
        Path jar = writeJar(moduleTarget.resolve("some-module.jar"), 10);

        Path resolved = JarResolver.resolve(temp, null);

        assertNotNull(resolved);
        assertEquals(jar, resolved);
    }

    @Test
    void returnsNullWhenNothingToUpload() throws IOException {
        Files.createDirectories(temp.resolve("target"));
        assertNull(JarResolver.resolve(temp, temp));
        assertNull(JarResolver.resolve(null, null));
    }

    @Test
    void candidateListHasNoDuplicatesWhenModuleIsProjectRoot() throws IOException {
        Path target = Files.createDirectories(temp.resolve("target"));
        writeJar(target.resolve("document-chunker-1.0.0.jar"), 10);

        List<Path> found = JarResolver.listCandidatesFor(temp, temp, 5);

        assertEquals(1, found.size());
        assertEquals("document-chunker-1.0.0.jar", found.get(0).getFileName().toString());
    }

    @Test
    void listsCandidatesForDiagnostics() throws IOException {
        Path moduleTarget = Files.createDirectories(temp.resolve("document-chunker/target"));
        writeJar(moduleTarget.resolve("document-chunker-1.0.0.jar"), 10);
        writeJar(moduleTarget.resolve("document-chunker-1.0.0-sources.jar"), 20);
        writeJar(moduleTarget.resolve("document-chunker-1.0.0.jar.original"), 30);

        List<Path> found = JarResolver.listCandidatesFor(temp, temp.resolve("document-chunker"), 5);

        assertEquals(List.of(moduleTarget.resolve("document-chunker-1.0.0.jar")), found);
    }

    @Test
    void deliverableJarFilter() {
        assertTrue(JarResolver.isDeliverableJar("app.jar"));
        assertTrue(JarResolver.isDeliverableJar("APP.JAR"));
        assertFalse(JarResolver.isDeliverableJar("app-sources.jar"));
        assertFalse(JarResolver.isDeliverableJar("app-javadoc.jar"));
        assertFalse(JarResolver.isDeliverableJar("app-tests.jar"));
        assertFalse(JarResolver.isDeliverableJar("original-app.jar"));
        assertFalse(JarResolver.isDeliverableJar("app.jar.original"));
        assertFalse(JarResolver.isDeliverableJar("app-plain.jar"));
        assertFalse(JarResolver.isDeliverableJar("app.txt"));
        assertFalse(JarResolver.isDeliverableJar(null));
    }

    @Test
    void candidatesSortedNewestFirst() throws IOException {
        Path target = Files.createDirectories(temp.resolve("target"));
        Path older = writeJar(target.resolve("older.jar"), 10);
        Files.setLastModifiedTime(older, FileTime.fromMillis(System.currentTimeMillis() - 60_000L));
        writeJar(target.resolve("newer.jar"), 10);

        List<JarResolver.Candidate> candidates = JarResolver.candidates(target);

        assertEquals(2, candidates.size());
        assertEquals("newer.jar", candidates.get(0).path().getFileName().toString());
    }

    private static Path writeJar(Path path, int size) throws IOException {
        Files.write(path, new byte[size]);
        return path;
    }
}
