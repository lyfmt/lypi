package cn.lypi.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryConsolidationWritePolicyTest {
    @TempDir
    Path tempDir;

    @Test
    void allowsUserMemoryTargets() throws IOException {
        Path cwd = tempDir.resolve("repo");
        Path userRoot = tempDir.resolve("home/.ly-pi");
        Files.createDirectories(userRoot.resolve("memories/nested"));
        MemoryConsolidationWritePolicy policy = new MemoryConsolidationWritePolicy(cwd, userRoot);

        assertTrue(policy.isAllowedWritePath(userRoot.resolve("memory.md").toString()));
        assertTrue(policy.isAllowedWritePath(userRoot.resolve("memories/guidance.md").toString()));
        assertTrue(policy.isAllowedWritePath(userRoot.resolve("memories/nested/guidance.md").toString()));
    }

    @Test
    void allowsProjectMemoryTargets() throws IOException {
        Path cwd = tempDir.resolve("repo");
        Path userRoot = tempDir.resolve("home/.ly-pi");
        Files.createDirectories(cwd.resolve(".ly-pi/memory/project"));
        Files.createDirectories(cwd.resolve(".ly-pi/skills/java/nested"));
        MemoryConsolidationWritePolicy policy = new MemoryConsolidationWritePolicy(cwd, userRoot);

        assertTrue(policy.isAllowedWritePath("MEMORY.md"));
        assertTrue(policy.isAllowedWritePath(".ly-pi/memory.md"));
        assertTrue(policy.isAllowedWritePath(".ly-pi/memory/project/facts.md"));
        assertTrue(policy.isAllowedWritePath(".ly-pi/skills/java/SKILL.md"));
        assertTrue(policy.isAllowedWritePath(".ly-pi/skills/java/nested/SKILL.md"));
    }

    @Test
    void deniesNonMemoryTargets() throws IOException {
        Path cwd = tempDir.resolve("repo");
        Path userRoot = tempDir.resolve("home/.ly-pi");
        Files.createDirectories(cwd);
        MemoryConsolidationWritePolicy policy = new MemoryConsolidationWritePolicy(cwd, userRoot);

        assertFalse(policy.isAllowedWritePath("src/Main.java"));
        assertFalse(policy.isAllowedWritePath("docs/foo.md"));
        assertFalse(policy.isAllowedWritePath(".git/config"));
        assertFalse(policy.isAllowedWritePath("pom.xml"));
    }

    @Test
    void deniesTraversalOutsideAllowedRoots() throws IOException {
        Path cwd = tempDir.resolve("repo");
        Path userRoot = tempDir.resolve("home/.ly-pi");
        Files.createDirectories(cwd.resolve(".ly-pi/memory"));
        Files.createDirectories(userRoot.resolve("memories"));
        MemoryConsolidationWritePolicy policy = new MemoryConsolidationWritePolicy(cwd, userRoot);

        assertFalse(policy.isAllowedWritePath(".ly-pi/memory/../../../src/Main.java"));
        assertFalse(policy.isAllowedWritePath(userRoot.resolve("memories/../../../secret.md").toString()));
    }

    @Test
    void deniesSymlinkEscapeFromAllowedRoots() throws IOException {
        Path cwd = tempDir.resolve("repo");
        Path outside = tempDir.resolve("outside");
        Path memoryParent = cwd.resolve(".ly-pi");
        Files.createDirectories(memoryParent);
        Files.createDirectories(outside);
        Files.createSymbolicLink(memoryParent.resolve("memory"), outside);
        MemoryConsolidationWritePolicy policy = new MemoryConsolidationWritePolicy(cwd, tempDir.resolve("home/.ly-pi"));

        assertFalse(policy.isAllowedWritePath(".ly-pi/memory/escaped.md"));
    }
}
