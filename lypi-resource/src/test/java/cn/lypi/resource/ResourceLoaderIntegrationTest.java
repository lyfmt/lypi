package cn.lypi.resource;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.skill.SkillSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResourceLoaderIntegrationTest {
    @TempDir
    Path tempDir;

    @Test
    void loadAggregatesUserProjectNestedAndExplicitResources() throws Exception {
        Path user = Files.createDirectories(tempDir.resolve("user/.ly-pi"));
        Path project = Files.createDirectories(tempDir.resolve("repo"));
        Path nested = Files.createDirectories(project.resolve("module"));
        Path explicit = Files.createDirectories(tempDir.resolve("explicit"));
        Files.writeString(project.resolve(".git"), "gitdir: /tmp/repo.git");
        Files.writeString(user.resolve("AGENTS.md"), "user agents");
        Files.writeString(project.resolve("AGENTS.md"), "project agents");
        Files.writeString(nested.resolve("AGENTS.md"), "nested agents");
        Files.writeString(explicit.resolve("AGENTS.md"), "explicit agents");
        writeSkill(user.resolve("skills/user/SKILL.md"), "user-skill", "User skill");
        writeSkill(nested.resolve(".ly-pi/skills/nested/SKILL.md"), "nested-skill", "Nested skill");

        var loader = new DefaultResourceLoader(List.of(user), List.of(explicit));
        var snapshot = loader.load(nested);

        assertThat(snapshot.agentFiles())
            .extracting(file -> file.content().strip())
            .containsExactly("user agents", "project agents", "nested agents", "explicit agents");
        assertThat(snapshot.skillIndex().skills())
            .extracting(skill -> skill.source() + ":" + skill.name())
            .containsExactly(
                SkillSource.USER + ":user-skill",
                SkillSource.NESTED_PROJECT + ":nested-skill"
            );
    }

    private void writeSkill(Path file, String name, String description) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
            ---
            name: %s
            description: %s
            ---
            body
            """.formatted(name, description));
    }
}
