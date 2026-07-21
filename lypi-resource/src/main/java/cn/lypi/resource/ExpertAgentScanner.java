package cn.lypi.resource;

import cn.lypi.contracts.resource.ResourceDiagnostic;
import cn.lypi.contracts.subagent.ExpertAgentDefinition;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Scans layered expert Agent definitions without exposing YAML details to callers. */
class ExpertAgentScanner {
    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");
    private static final Set<String> FIELDS = Set.of("name", "provider", "model", "prompt", "tools");

    private final YAMLMapper yamlMapper = YAMLMapper.builder()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
        .build();

    List<ExpertAgentDefinition> scan(
        List<ResourceLocation> locations,
        List<ResourceDiagnostic> diagnostics
    ) {
        Map<String, PrioritizedResource<ExpertAgentDefinition>> selected = new LinkedHashMap<>();
        for (ResourceLocation location : orderedLocations(locations)) {
            for (Path file : agentFiles(location, diagnostics)) {
                readAgent(location, file, diagnostics, selected);
            }
        }
        return selected.values().stream().map(PrioritizedResource::value).toList();
    }

    private List<ResourceLocation> orderedLocations(List<ResourceLocation> locations) {
        return locations.stream()
            .filter(location -> switch (location.layer()) {
                case USER, PROJECT, NESTED_PROJECT, EXPLICIT_PATH -> true;
                case PLATFORM, SESSION, MCP_DERIVED -> false;
            })
            .sorted(Comparator.comparingInt(ResourceLocation::priority))
            .toList();
    }

    private List<Path> agentFiles(ResourceLocation location, List<ResourceDiagnostic> diagnostics) {
        Path root = switch (location.layer()) {
            case USER -> location.root().resolve("agents");
            case PROJECT, NESTED_PROJECT, EXPLICIT_PATH -> location.root().resolve(".ly-pi").resolve("agents");
            default -> null;
        };
        if (root == null || !Files.isDirectory(root)) {
            return List.of();
        }
        return ResourceFiles.regularFiles(root, diagnostics).stream()
            .filter(this::isYaml)
            .toList();
    }

    private boolean isYaml(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(".yaml") || name.endsWith(".yml");
    }

    private void readAgent(
        ResourceLocation location,
        Path file,
        List<ResourceDiagnostic> diagnostics,
        Map<String, PrioritizedResource<ExpertAgentDefinition>> selected
    ) {
        try {
            JsonNode root = yamlMapper.readTree(file.toFile());
            validateShape(root);
            AgentYaml yaml = yamlMapper.treeToValue(root, AgentYaml.class);
            ExpertAgentDefinition agent = toDefinition(yaml, file);
            mergeAgent(agent, location, file, diagnostics, selected);
        } catch (IOException | RuntimeException exception) {
            diagnostics.add(ResourceDiagnostics.warning(
                "Failed to parse expert agent: " + exception.getMessage(),
                file
            ));
        }
    }

    private void validateShape(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("definition must be a YAML object");
        }
        root.fieldNames().forEachRemaining(field -> {
            if (!FIELDS.contains(field)) {
                throw new IllegalArgumentException("unknown field: " + field);
            }
        });
        for (String field : List.of("name", "provider", "model", "prompt")) {
            JsonNode value = root.get(field);
            if (value != null && !value.isNull() && !value.isTextual()) {
                throw new IllegalArgumentException(field + " must be a string");
            }
        }
        JsonNode tools = root.get("tools");
        if (tools == null || tools.isNull()) {
            return;
        }
        if (!tools.isArray()) {
            throw new IllegalArgumentException("tools must be an array");
        }
        tools.forEach(tool -> {
            if (!tool.isTextual()) {
                throw new IllegalArgumentException("tools entries must be strings");
            }
        });
    }

    private ExpertAgentDefinition toDefinition(AgentYaml yaml, Path file) {
        if (yaml == null) {
            throw new IllegalArgumentException("definition must be a YAML object");
        }
        String name = required("name", yaml.name());
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException("name must match " + NAME_PATTERN.pattern());
        }
        List<String> tools = validatedTools(yaml.tools());
        return new ExpertAgentDefinition(
            name,
            required("provider", yaml.provider()),
            required("model", yaml.model()),
            required("prompt", yaml.prompt()),
            tools,
            file
        );
    }

    private String required(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must be a non-empty string");
        }
        return value.trim();
    }

    private List<String> validatedTools(List<String> tools) {
        if (tools == null || tools.isEmpty()) {
            return List.of();
        }
        List<String> validated = new ArrayList<>(tools.size());
        for (String tool : tools) {
            validated.add(required("tools entry", tool));
        }
        return List.copyOf(validated);
    }

    private void mergeAgent(
        ExpertAgentDefinition agent,
        ResourceLocation location,
        Path file,
        List<ResourceDiagnostic> diagnostics,
        Map<String, PrioritizedResource<ExpertAgentDefinition>> selected
    ) {
        PrioritizedResource<ExpertAgentDefinition> existing = selected.get(agent.name());
        if (existing == null) {
            selected.put(agent.name(), new PrioritizedResource<>(agent, location.priority(), location));
            return;
        }
        if (location.priority() >= existing.priority()) {
            diagnostics.add(ResourceDiagnostics.warning("expert agent override: " + agent.name(), file));
            selected.put(agent.name(), new PrioritizedResource<>(agent, location.priority(), location));
        } else {
            diagnostics.add(ResourceDiagnostics.warning(
                "expert agent shadowed by higher priority definition: " + agent.name(),
                file
            ));
        }
    }

    private record AgentYaml(
        String name,
        String provider,
        String model,
        String prompt,
        List<String> tools
    ) {}
}
