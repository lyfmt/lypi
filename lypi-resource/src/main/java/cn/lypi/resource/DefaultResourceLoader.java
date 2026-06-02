package cn.lypi.resource;

import cn.lypi.contracts.mcp.McpServerConfig;
import cn.lypi.contracts.mcp.McpTransport;
import cn.lypi.contracts.prompt.PromptParameter;
import cn.lypi.contracts.prompt.PromptTemplate;
import cn.lypi.contracts.prompt.PromptTemplateSource;
import cn.lypi.contracts.resource.ContextFile;
import cn.lypi.contracts.resource.MemorySource;
import cn.lypi.contracts.resource.ResourceDiagnostic;
import cn.lypi.contracts.resource.ResourceDiagnosticLevel;
import cn.lypi.contracts.resource.ResourceSnapshot;
import cn.lypi.contracts.skill.SkillDescriptor;
import cn.lypi.contracts.skill.SkillIndex;
import cn.lypi.contracts.skill.SkillSource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

public class DefaultResourceLoader implements ResourceLoader {
    private static final List<String> CONTEXT_FILE_NAMES = List.of(
        "AGENTS.md",
        "CLAUDE.md",
        "SYSTEM.md",
        "APPEND_SYSTEM.md"
    );

    private final FrontmatterParser frontmatterParser = new FrontmatterParser();
    private final ObjectMapper jsonMapper = new ObjectMapper();

    @Override
    public ResourceSnapshot load(Path cwd) {
        Path start = cwd.toAbsolutePath().normalize();
        Path projectRoot = resolveProjectRoot(start);
        List<ResourceDiagnostic> diagnostics = new ArrayList<>();
        List<Path> searchDirectories = directoriesFromRoot(projectRoot, start);

        List<ContextFile> agentFiles = loadContextFiles(searchDirectories, diagnostics);
        List<MemorySource> memorySources = loadMemorySources(projectRoot, diagnostics);
        SkillIndex skillIndex = loadSkillIndex(projectRoot, diagnostics);
        List<PromptTemplate> promptTemplates = loadPromptTemplates(projectRoot, diagnostics);
        List<McpServerConfig> mcpServers = loadMcpServers(projectRoot, diagnostics);

        return new ResourceSnapshot(
            agentFiles,
            memorySources,
            skillIndex,
            promptTemplates,
            mcpServers,
            List.copyOf(diagnostics)
        );
    }

    private Path resolveProjectRoot(Path cwd) {
        Path current = Files.isRegularFile(cwd) ? cwd.getParent() : cwd;
        Path nearestPomDirectory = null;
        while (current != null) {
            if (Files.exists(current.resolve(".git"))) {
                return current;
            }
            if (nearestPomDirectory == null && Files.exists(current.resolve("pom.xml"))) {
                nearestPomDirectory = current;
            }
            current = current.getParent();
        }
        return nearestPomDirectory == null ? cwd : nearestPomDirectory;
    }

    private List<Path> directoriesFromRoot(Path projectRoot, Path cwd) {
        Path current = Files.isRegularFile(cwd) ? cwd.getParent() : cwd;
        List<Path> directories = new ArrayList<>();
        while (current != null) {
            directories.add(current);
            if (current.equals(projectRoot)) {
                break;
            }
            current = current.getParent();
        }
        directories.sort(Comparator.comparingInt(path -> path.getNameCount()));
        return directories;
    }

    private List<ContextFile> loadContextFiles(List<Path> directories, List<ResourceDiagnostic> diagnostics) {
        List<ContextFile> files = new ArrayList<>();
        for (Path directory : directories) {
            for (String fileName : CONTEXT_FILE_NAMES) {
                Path file = directory.resolve(fileName);
                if (Files.isRegularFile(file)) {
                    readString(file, diagnostics).ifPresent(content ->
                        files.add(new ContextFile(file, content, Hashing.sha256(content)))
                    );
                }
            }
        }
        return List.copyOf(files);
    }

    private List<MemorySource> loadMemorySources(Path projectRoot, List<ResourceDiagnostic> diagnostics) {
        List<MemorySource> sources = new ArrayList<>();
        Path rootMemory = projectRoot.resolve("MEMORY.md");
        if (Files.isRegularFile(rootMemory)) {
            readString(rootMemory, diagnostics).ifPresent(content ->
                sources.add(new MemorySource(rootMemory, Hashing.sha256(content)))
            );
        }
        Path memoryRoot = projectRoot.resolve(".ly-pi").resolve("memory");
        if (Files.isDirectory(memoryRoot)) {
            for (Path file : regularFiles(memoryRoot, diagnostics)) {
                readString(file, diagnostics).ifPresent(content ->
                    sources.add(new MemorySource(file, Hashing.sha256(content)))
                );
            }
        }
        return List.copyOf(sources);
    }

    private SkillIndex loadSkillIndex(Path projectRoot, List<ResourceDiagnostic> diagnostics) {
        List<ResourceDiagnostic> skillDiagnostics = new ArrayList<>();
        List<SkillDescriptor> skills = new ArrayList<>();
        Path skillsRoot = projectRoot.resolve(".ly-pi").resolve("skills");
        if (!Files.isDirectory(skillsRoot)) {
            return new SkillIndex(List.of(), List.of());
        }

        for (Path file : regularFiles(skillsRoot, diagnostics).stream()
            .filter(path -> path.getFileName().toString().equals("SKILL.md"))
            .toList()) {
            readString(file, diagnostics).ifPresent(content -> {
                try {
                    FrontmatterDocument document = frontmatterParser.parse(content);
                    String name = stringValue(document.metadata(), "name")
                        .orElseGet(() -> file.getParent().getFileName().toString());
                    String description = stringValue(document.metadata(), "description").orElse("");
                    skills.add(new SkillDescriptor(
                        name,
                        description,
                        SkillSource.PROJECT,
                        file,
                        stringList(document.metadata().get("paths")),
                        stringList(document.metadata().get("allowed_tools")),
                        Hashing.sha256(content)
                    ));
                } catch (IOException exception) {
                    ResourceDiagnostic diagnostic = warning("Failed to parse skill frontmatter: " + exception.getMessage(), file);
                    diagnostics.add(diagnostic);
                    skillDiagnostics.add(diagnostic);
                }
            });
        }

        Set<String> seenNames = new HashSet<>();
        for (SkillDescriptor skill : skills) {
            if (!seenNames.add(skill.name())) {
                ResourceDiagnostic diagnostic = warning("duplicate skill name: " + skill.name(), skill.skillFile());
                diagnostics.add(diagnostic);
                skillDiagnostics.add(diagnostic);
            }
        }
        return new SkillIndex(List.copyOf(skills), List.copyOf(skillDiagnostics));
    }

    private List<PromptTemplate> loadPromptTemplates(Path projectRoot, List<ResourceDiagnostic> diagnostics) {
        List<PromptTemplate> templates = new ArrayList<>();
        Path promptsRoot = projectRoot.resolve(".ly-pi").resolve("prompts");
        if (!Files.isDirectory(promptsRoot)) {
            return List.of();
        }

        for (Path file : regularFiles(promptsRoot, diagnostics).stream()
            .filter(path -> path.getFileName().toString().endsWith(".md"))
            .toList()) {
            readString(file, diagnostics).ifPresent(content -> {
                try {
                    FrontmatterDocument document = frontmatterParser.parse(content);
                    String name = stringValue(document.metadata(), "name")
                        .orElseGet(() -> stripExtension(file.getFileName().toString()));
                    String description = stringValue(document.metadata(), "description").orElse("");
                    templates.add(new PromptTemplate(
                        name,
                        description,
                        PromptTemplateSource.PROJECT,
                        promptParameters(document.metadata().get("parameters")),
                        document.body(),
                        Hashing.sha256(content)
                    ));
                } catch (IOException exception) {
                    diagnostics.add(warning("Failed to parse prompt template: " + exception.getMessage(), file));
                }
            });
        }
        return List.copyOf(templates);
    }

    private List<McpServerConfig> loadMcpServers(Path projectRoot, List<ResourceDiagnostic> diagnostics) {
        List<McpServerConfig> servers = new ArrayList<>();
        Path lypiRoot = projectRoot.resolve(".ly-pi");
        Path mcpJson = lypiRoot.resolve("mcp.json");
        if (Files.isRegularFile(mcpJson)) {
            readMcpConfig(mcpJson, diagnostics).forEach(servers::add);
        }
        Path mcpDirectory = lypiRoot.resolve("mcp");
        if (Files.isDirectory(mcpDirectory)) {
            for (Path file : regularFiles(mcpDirectory, diagnostics).stream()
                .filter(path -> path.getFileName().toString().endsWith(".json"))
                .toList()) {
                readMcpConfig(file, diagnostics).forEach(servers::add);
            }
        }
        return List.copyOf(servers);
    }

    private List<McpServerConfig> readMcpConfig(Path file, List<ResourceDiagnostic> diagnostics) {
        try {
            JsonNode root = jsonMapper.readTree(file.toFile());
            JsonNode serversNode = root.path("servers");
            if (!serversNode.isObject()) {
                diagnostics.add(warning("mcp config does not contain servers object", file));
                return List.of();
            }
            List<McpServerConfig> servers = new ArrayList<>();
            serversNode.properties().forEach(entry -> servers.add(toMcpServer(entry.getKey(), entry.getValue())));
            return servers;
        } catch (RuntimeException | IOException exception) {
            diagnostics.add(warning("Failed to parse mcp config: " + exception.getMessage(), file));
            return List.of();
        }
    }

    private McpServerConfig toMcpServer(String name, JsonNode node) {
        McpTransport transport = McpTransport.valueOf(node.path("transport").asText("STDIO").toUpperCase());
        List<String> command = new ArrayList<>();
        node.path("command").forEach(part -> command.add(part.asText()));
        Map<String, String> env = new LinkedHashMap<>();
        node.path("env").properties().forEach(entry -> env.put(entry.getKey(), entry.getValue().asText()));
        return new McpServerConfig(
            name,
            transport,
            List.copyOf(command),
            Map.copyOf(env),
            Duration.ofSeconds(node.path("startupTimeoutSeconds").asLong(10)),
            Duration.ofSeconds(node.path("callTimeoutSeconds").asLong(60))
        );
    }

    private List<Path> regularFiles(Path root, List<ResourceDiagnostic> diagnostics) {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream
                .filter(Files::isRegularFile)
                .sorted()
                .toList();
        } catch (IOException exception) {
            diagnostics.add(warning("Failed to scan resource directory: " + exception.getMessage(), root));
            return List.of();
        }
    }

    private Optional<String> readString(Path file, List<ResourceDiagnostic> diagnostics) {
        try {
            return Optional.of(Files.readString(file));
        } catch (IOException exception) {
            diagnostics.add(warning("Failed to read resource file: " + exception.getMessage(), file));
            return Optional.empty();
        }
    }

    private List<PromptParameter> promptParameters(Object value) {
        if (!(value instanceof List<?> items)) {
            return List.of();
        }
        List<PromptParameter> parameters = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> metadata = stringKeyMap(map);
                parameters.add(new PromptParameter(
                    stringValue(metadata, "name").orElse(""),
                    stringValue(metadata, "description").orElse(""),
                    booleanValue(metadata.get("required")),
                    stringValue(metadata, "default")
                ));
            }
        }
        return List.copyOf(parameters);
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> items)) {
            return List.of();
        }
        return items.stream().map(String::valueOf).toList();
    }

    private Map<String, Object> stringKeyMap(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private Optional<String> stringValue(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        return value == null ? Optional.empty() : Optional.of(String.valueOf(value));
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private String stripExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex < 0 ? fileName : fileName.substring(0, dotIndex);
    }

    private ResourceDiagnostic warning(String message, Path path) {
        return new ResourceDiagnostic(ResourceDiagnosticLevel.WARNING, message, Optional.of(path));
    }
}
