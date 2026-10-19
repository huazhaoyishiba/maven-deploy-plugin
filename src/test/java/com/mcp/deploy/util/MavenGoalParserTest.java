package com.mcp.deploy.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 打包目标判定单元测试。
 *
 * <p>这里的用例大多来自真实踩坑：IDEA 生成的 Maven 运行配置名是
 * {@code module [clean, package]}，旧实现的正则 {@code .*\[package|install|deploy].*}
 * 匹配不到多目标组合，于是「有时候上传不了」。</p>
 */
class MavenGoalParserTest {

    @Test
    void parsesGoalsFromBracketedConfigName() {
        assertEquals(List.of("clean", "package"), MavenGoalParser.goalsFromName("demo [clean, package]"));
        assertEquals(List.of("package"), MavenGoalParser.goalsFromName("demo [package]"));
        assertEquals(List.of("clean", "install"), MavenGoalParser.goalsFromName("demo-parent [clean, install]"));
        assertEquals(List.of("clean", "deploy"), MavenGoalParser.goalsFromName("demo [clean deploy]"));
    }

    @Test
    void multiGoalRunWithPackageIsPackaging() {
        // 旧实现在这个用例上匹配失败 → 不触发上传
        assertTrue(MavenGoalParser.hasPackagingGoal(
            MavenGoalParser.goalsFromName("maven-deploy-plugin [clean, package]")));
        assertTrue(MavenGoalParser.hasPackagingGoal(
            MavenGoalParser.goalsFromName("maven-deploy-plugin [clean, install]")));
        assertTrue(MavenGoalParser.hasPackagingGoal(
            MavenGoalParser.goalsFromName("maven-deploy-plugin [deploy]")));
    }

    @Test
    void nonPackagingGoalsDoNotTrigger() {
        assertFalse(MavenGoalParser.hasPackagingGoal(MavenGoalParser.goalsFromName("demo [clean]")));
        assertFalse(MavenGoalParser.hasPackagingGoal(MavenGoalParser.goalsFromName("demo [compile]")));
        assertFalse(MavenGoalParser.hasPackagingGoal(MavenGoalParser.goalsFromName("demo [test]")));
        assertFalse(MavenGoalParser.hasPackagingGoal(MavenGoalParser.goalsFromName("demo [validate]")));
        // deploy 只作为名称前缀出现，目标里没有 deploy
        assertFalse(MavenGoalParser.nameSuggestsPackaging("deploy-tool [clean]"));
    }

    @Test
    void ignoresMavenFlagsWhenParsingGoals() {
        List<String> goals = MavenGoalParser.goalsFromName("demo [clean, package, -DskipTests, -Pprod]");
        assertEquals(List.of("clean", "package"), goals);
        assertTrue(MavenGoalParser.hasPackagingGoal(goals));
    }

    @Test
    void supportsPluginQualifiedGoals() {
        assertTrue(MavenGoalParser.isPackagingGoal("org.apache.maven.plugins:maven-jar-plugin:package"));
        assertTrue(MavenGoalParser.isPackagingGoal("PACKAGE"));
        assertFalse(MavenGoalParser.isPackagingGoal("org.springframework.boot:spring-boot-maven-plugin:repackage"));
    }

    @Test
    void nameFallbackWorksWhenNoBracketPresent() {
        assertTrue(MavenGoalParser.nameSuggestsPackaging("demo package"));
        assertTrue(MavenGoalParser.nameSuggestsPackaging("demo [clean, install]"));
        assertFalse(MavenGoalParser.nameSuggestsPackaging("demo clean"));
        assertFalse(MavenGoalParser.nameSuggestsPackaging("打包部署"));
        assertFalse(MavenGoalParser.nameSuggestsPackaging(""));
        assertFalse(MavenGoalParser.nameSuggestsPackaging(null));
    }

    @Test
    void packagingGoalReturnsMatchedGoal() {
        assertEquals("package", MavenGoalParser.packagingGoal(List.of("clean", "package")));
        assertNull(MavenGoalParser.packagingGoal(List.of("clean", "compile")));
        assertNull(MavenGoalParser.packagingGoal(null));
    }
}
