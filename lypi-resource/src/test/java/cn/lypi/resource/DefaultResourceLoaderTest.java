package cn.lypi.resource;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.mcp.McpTransport;
import cn.lypi.contracts.prompt.PromptTemplateSource;
import cn.lypi.contracts.resource.ResourceDiagnosticLevel;
import cn.lypi.contracts.resource.ResourceSnapshot;
import cn.lypi.contracts.skill.SkillSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DefaultResourceLoaderTest {
    @TempDir
    Path tempDir;

    @Test
    void loadDiscoversContextFilesFromProjectRootToCurrentDirectory() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("repo"));
        Path module = Files.createDirectories(root.resolve("module"));
        Files.writeString(root.resolve(".git"), "gitdir: /tmp/repo.git");
        Files.writeString(root.resolve("AGENTS.md"), "root agents");
        Files.writeString(root.resolve("SYSTEM.md"), "root system");
        Files.writeString(module.resolve("CLAUDE.md"), "module claude");
        Files.writeString(module.resolve("APPEND_SYSTEM.md"), "module append");

        ResourceSnapshot snapshot = new DefaultResourceLoader(List.of(), List.of()).load(module);

        assertThat(snapshot.agentFiles())
            .extracting(file -> root.relativize(file.path()).toString())
            .containsExactly("SYSTEM.md", "AGENTS.md", "module/APPEND_SYSTEM.md", "module/CLAUDE.md");
        assertThat(snapshot.agentFiles()).allSatisfy(file -> assertThat(file.contentHash()).startsWith("sha256:"));
        assertThat(snapshot.diagnostics()).isEmpty();
    }

    @Test
    void loadUsesGitRootWhenCurrentDirectoryIsAMavenSubmodule() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("repo"));
        Path module = Files.createDirectories(root.resolve("lypi-resource"));
        Files.writeString(root.resolve(".git"), "gitdir: /tmp/repo.git");
        Files.writeString(root.resolve("AGENTS.md"), "root agents");
        Files.writeString(module.resolve("pom.xml"), "<project/>");
        Files.writeString(module.resolve("SYSTEM.md"), "module system");

        ResourceSnapshot snapshot = new DefaultResourceLoader(List.of(), List.of()).load(module);

        assertThat(snapshot.agentFiles())
            .extracting(file -> root.relativize(file.path()).toString())
            .containsExactly("AGENTS.md", "lypi-resource/SYSTEM.md");
    }

    @Test
    void loadDiscoversMemoryPromptTemplateSkillAndMcpConfig() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("repo"));
        Files.writeString(root.resolve(".git"), "gitdir: /tmp/repo.git");
        Files.writeString(root.resolve("MEMORY.md"), "project memory");
        Path memoryDir = Files.createDirectories(root.resolve(".ly-pi/memory/project"));
        Files.writeString(memoryDir.resolve("facts.md"), "remember this");
        Path skillDir = Files.createDirectories(root.resolve(".ly-pi/skills/java"));
        Files.writeString(skillDir.resolve("SKILL.md"), """
            ---
            name: java-style
            description: Java style workflow
            paths:
              - "**/*.java"
            allowed_tools:
              - read
              - edit
            ---
            Body should stay out of the index.
            """);
        Path promptDir = Files.createDirectories(root.resolve(".ly-pi/prompts"));
        Files.writeString(promptDir.resolve("review.md"), """
            ---
            name: review
            description: Review current changes
            parameters:
              - name: scope
                description: Review scope
                required: true
              - name: tone
                description: Reply tone
                default: concise
            ---
            Review {{scope}}.
            """);
        Files.writeString(root.resolve(".ly-pi/mcp.json"), """
            {
              "servers": {
                "filesystem": {
                  "transport": "STDIO",
                  "command": ["node", "server.js"],
                  "env": {"ROOT": "/tmp"},
                  "startupTimeoutSeconds": 3,
                  "callTimeoutSeconds": 9
                }
              }
            }
            """);

        ResourceSnapshot snapshot = new DefaultResourceLoader(List.of(), List.of()).load(root);

        assertThat(snapshot.memorySources())
            .extracting(source -> root.relativize(source.path()).toString())
            .containsExactly("MEMORY.md", ".ly-pi/memory/project/facts.md");
        assertThat(snapshot.skillIndex().skills()).singleElement().satisfies(skill -> {
            assertThat(skill.name()).isEqualTo("java-style");
            assertThat(skill.description()).isEqualTo("Java style workflow");
            assertThat(skill.source()).isEqualTo(SkillSource.PROJECT);
            assertThat(skill.pathGlobs()).containsExactly("**/*.java");
            assertThat(skill.allowedTools()).containsExactly("read", "edit");
            assertThat(skill.contentHash()).startsWith("sha256:");
        });
        assertThat(snapshot.promptTemplates()).singleElement().satisfies(template -> {
            assertThat(template.name()).isEqualTo("review");
            assertThat(template.description()).isEqualTo("Review current changes");
            assertThat(template.source()).isEqualTo(PromptTemplateSource.PROJECT);
            assertThat(template.templateBody()).isEqualTo("Review {{scope}}.");
            assertThat(template.parameters()).hasSize(2);
            assertThat(template.parameters().get(0).required()).isTrue();
            assertThat(template.parameters().get(1).defaultValue()).isEqualTo(Optional.of("concise"));
        });
        assertThat(snapshot.mcpServers()).singleElement().satisfies(server -> {
            assertThat(server.name()).isEqualTo("filesystem");
            assertThat(server.transport()).isEqualTo(McpTransport.STDIO);
            assertThat(server.stdio().command()).containsExactly("node", "server.js");
            assertThat(server.stdio().env()).containsEntry("ROOT", "/tmp");
            assertThat(server.http()).isNull();
            assertThat(server.startupTimeout()).isEqualTo(Duration.ofSeconds(3));
            assertThat(server.callTimeout()).isEqualTo(Duration.ofSeconds(9));
        });
        assertThat(snapshot.diagnostics()).isEmpty();
    }

    @Test
    void loadDiscoversGlobalMemoryIndexButDoesNotScanL1Memories() throws Exception {
        Path user = Files.createDirectories(tempDir.resolve("user/.ly-pi"));
        Path project = Files.createDirectories(tempDir.resolve("repo"));
        Files.writeString(project.resolve(".git"), "gitdir: /tmp/repo.git");

        Files.createDirectories(user.resolve("memories"));
        Files.writeString(user.resolve("memory.md"), "L0 index");
        Files.writeString(user.resolve("memories/guidance.md"), "L1 guidance");

        ResourceSnapshot snapshot = new DefaultResourceLoader(List.of(user), List.of()).load(project);

        assertThat(snapshot.memorySources())
            .extracting(source -> source.path())
            .anySatisfy(path -> assertThat(path).endsWith(Path.of("memory.md")))
            .noneSatisfy(path -> assertThat(path).endsWith(Path.of("memories", "guidance.md")));
    }

    @Test
    void loadDiscoversUserLevelSkills() throws Exception {
        Path user = Files.createDirectories(tempDir.resolve("user/.ly-pi"));
        Path project = Files.createDirectories(tempDir.resolve("repo"));
        Files.writeString(project.resolve(".git"), "gitdir: /tmp/repo.git");
        Path skillDir = Files.createDirectories(user.resolve("skills/memory-settlement"));
        Files.writeString(skillDir.resolve("SKILL.md"), """
            ---
            name: memory-settlement
            description: Use when a long task, important correction, repeated failure, project handoff, or reusable lesson may need durable memory.
            ---
            Body should not enter the resource index.
            """);

        ResourceSnapshot snapshot = new DefaultResourceLoader(List.of(user), List.of()).load(project);

        assertThat(snapshot.skillIndex().skills()).singleElement().satisfies(skill -> {
            assertThat(skill.name()).isEqualTo("memory-settlement");
            assertThat(skill.source()).isEqualTo(SkillSource.USER);
            assertThat(skill.skillFile()).isEqualTo(skillDir.resolve("SKILL.md").toAbsolutePath().normalize());
        });
    }

    @Test
    void defaultLoaderDiscoversInitializedMemorySettlementSkill() throws Exception {
        Path home = Files.createDirectories(tempDir.resolve("home"));
        Path project = Files.createDirectories(tempDir.resolve("repo"));
        Files.writeString(project.resolve(".git"), "gitdir: /tmp/repo.git");
        Properties properties = System.getProperties();
        String previousHome = properties.getProperty("user.home");
        properties.setProperty("user.home", home.toString());
        try {
            ResourceSnapshot snapshot = new DefaultResourceLoader().load(project);

            assertThat(snapshot.skillIndex().skills())
                .anySatisfy(skill -> {
                    assertThat(skill.name()).isEqualTo("memory-settlement");
                    assertThat(skill.source()).isEqualTo(SkillSource.USER);
                    assertThat(skill.skillFile()).isEqualTo(home.resolve(".ly-pi/skills/memory-settlement/SKILL.md").toAbsolutePath().normalize());
                });
            assertThat(snapshot.skillIndex().skills())
                .anySatisfy(skill -> {
                    assertThat(skill.name()).isEqualTo("memory-lint");
                    assertThat(skill.source()).isEqualTo(SkillSource.USER);
                    assertThat(skill.skillFile()).isEqualTo(home.resolve(".ly-pi/skills/memory-lint/SKILL.md").toAbsolutePath().normalize());
                });
            assertThat(snapshot.promptTemplates())
                .anySatisfy(template -> {
                    assertThat(template.name()).isEqualTo("memory-lint");
                    assertThat(template.templateBody()).contains("$memory-lint").contains("{{layers}}");
                });
        } finally {
            if (previousHome == null) {
                properties.remove("user.home");
            } else {
                properties.setProperty("user.home", previousHome);
            }
        }
    }

    @Test
    void loadDiscoversDotLyPiDirectorySkillsFromCurrentWorkingDirectory() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("repo"));
        Path module = Files.createDirectories(root.resolve("module"));
        Files.writeString(root.resolve(".git"), "gitdir: /tmp/repo.git");
        Path skillDir = Files.createDirectories(module.resolve(".ly-pi/skills/doc"));
        Files.writeString(skillDir.resolve("SKILL.md"), """
            ---
            name: doc
            description: Document workflow
            ---
            Body should not enter the resource index.
            """);

        ResourceSnapshot snapshot = new DefaultResourceLoader(List.of(), List.of()).load(module);

        assertThat(snapshot.skillIndex().skills()).singleElement().satisfies(skill -> {
            assertThat(skill.name()).isEqualTo("doc");
            assertThat(skill.description()).isEqualTo("Document workflow");
            assertThat(skill.source()).isEqualTo(SkillSource.NESTED_PROJECT);
            assertThat(skill.skillFile()).isEqualTo(skillDir.resolve("SKILL.md").toAbsolutePath().normalize());
        });
    }

    @Test
    void loadReportsDiagnosticsForInvalidResourcesWithoutFailingSnapshot() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("repo"));
        Files.writeString(root.resolve(".git"), "gitdir: /tmp/repo.git");
        Path firstSkill = Files.createDirectories(root.resolve(".ly-pi/skills/one"));
        Path secondSkill = Files.createDirectories(root.resolve(".ly-pi/skills/two"));
        Files.writeString(firstSkill.resolve("SKILL.md"), """
            ---
            name: duplicate
            description: First
            ---
            first
            """);
        Files.writeString(secondSkill.resolve("SKILL.md"), """
            ---
            name: duplicate
            description: Second
            ---
            second
            """);
        Files.createDirectories(root.resolve(".ly-pi"));
        Files.writeString(root.resolve(".ly-pi/mcp.json"), "{not-json");

        ResourceSnapshot snapshot = new DefaultResourceLoader(List.of(), List.of()).load(root);

        assertThat(snapshot.skillIndex().skills()).hasSize(2);
        assertThat(snapshot.diagnostics())
            .extracting(diagnostic -> diagnostic.level() + ":" + diagnostic.message())
            .anySatisfy(message -> assertThat(message).contains(ResourceDiagnosticLevel.WARNING.name()).contains("duplicate"))
            .anySatisfy(message -> assertThat(message).contains(ResourceDiagnosticLevel.WARNING.name()).contains("mcp"));
        assertThat(snapshot.skillIndex().diagnostics()).isNotEmpty();
    }
}
