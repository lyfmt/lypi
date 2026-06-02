package cn.lypi.resource;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectRootResolverTest {
    @TempDir
    Path tempDir;

    @Test
    void resolveUsesGitDirectoryInsteadOfMavenSubmoduleMarker() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("repo"));
        Path module = Files.createDirectories(root.resolve("module"));
        Files.createDirectory(root.resolve(".git"));
        Files.writeString(module.resolve("pom.xml"), "<project/>");

        ResourceDiscoveryPlan plan = new ProjectRootResolver().resolve(module);

        assertThat(plan.projectRoot()).isEqualTo(root);
        assertThat(plan.cwd()).isEqualTo(module);
        assertThat(plan.diagnostics()).isEmpty();
    }

    @Test
    void resolveUsesCwdWhenNoGitMarkerExists() throws Exception {
        Path cwd = Files.createDirectories(tempDir.resolve("not-git/module"));
        Files.writeString(tempDir.resolve("not-git/pom.xml"), "<project/>");

        ResourceDiscoveryPlan plan = new ProjectRootResolver().resolve(cwd);

        assertThat(plan.projectRoot()).isEqualTo(cwd);
        assertThat(plan.cwd()).isEqualTo(cwd);
        assertThat(plan.diagnostics()).isEmpty();
    }

    @Test
    void resolveSupportsGitFileUsedByWorktrees() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("repo"));
        Path module = Files.createDirectories(root.resolve("nested/module"));
        Files.writeString(root.resolve(".git"), "gitdir: /tmp/repo/.git/worktrees/resource");

        ResourceDiscoveryPlan plan = new ProjectRootResolver().resolve(module);

        assertThat(plan.projectRoot()).isEqualTo(root);
        assertThat(plan.cwd()).isEqualTo(module);
    }
}
