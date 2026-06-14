package cn.lypi.tool;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryConsolidationWritePolicyTest {
    private final Path cwd = Path.of("/repo").toAbsolutePath().normalize();
    private final Path userRoot = Path.of("/home/user/.ly-pi").toAbsolutePath().normalize();
    private final MemoryConsolidationWritePolicy policy = new MemoryConsolidationWritePolicy(cwd, userRoot);

    @Test
    void allowsUserMemoryTargets() {
        assertTrue(policy.isAllowedWritePath("/home/user/.ly-pi/memory.md"));
        assertTrue(policy.isAllowedWritePath("/home/user/.ly-pi/memories/guidance.md"));
        assertTrue(policy.isAllowedWritePath("/home/user/.ly-pi/memories/nested/guidance.md"));
    }

    @Test
    void allowsProjectMemoryTargets() {
        assertTrue(policy.isAllowedWritePath("MEMORY.md"));
        assertTrue(policy.isAllowedWritePath(".ly-pi/memory.md"));
        assertTrue(policy.isAllowedWritePath(".ly-pi/memory/project/facts.md"));
        assertTrue(policy.isAllowedWritePath(".ly-pi/skills/java/SKILL.md"));
        assertTrue(policy.isAllowedWritePath(".ly-pi/skills/java/nested/SKILL.md"));
    }

    @Test
    void deniesNonMemoryTargets() {
        assertFalse(policy.isAllowedWritePath("src/Main.java"));
        assertFalse(policy.isAllowedWritePath("docs/foo.md"));
        assertFalse(policy.isAllowedWritePath(".git/config"));
        assertFalse(policy.isAllowedWritePath("pom.xml"));
    }

    @Test
    void deniesTraversalOutsideAllowedRoots() {
        assertFalse(policy.isAllowedWritePath(".ly-pi/memory/../../../src/Main.java"));
        assertFalse(policy.isAllowedWritePath("/home/user/.ly-pi/memories/../../../secret.md"));
    }
}
