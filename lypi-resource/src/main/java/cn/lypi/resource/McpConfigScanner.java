package cn.lypi.resource;

import cn.lypi.contracts.mcp.McpServerConfig;
import cn.lypi.contracts.mcp.McpTransport;
import cn.lypi.contracts.resource.ResourceDiagnostic;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class McpConfigScanner {
    private final ObjectMapper jsonMapper = new ObjectMapper();

    List<McpServerConfig> scan(List<ResourceLocation> locations, List<ResourceDiagnostic> diagnostics) {
        Map<String, PrioritizedResource<McpServerConfig>> selected = new LinkedHashMap<>();
        for (ResourceLocation location : orderedLocations(locations)) {
            for (Path file : mcpConfigFiles(location, diagnostics)) {
                readMcpConfig(location, file, diagnostics, selected);
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

    private List<Path> mcpConfigFiles(ResourceLocation location, List<ResourceDiagnostic> diagnostics) {
        List<Path> files = new ArrayList<>();
        Path direct = switch (location.layer()) {
            case USER -> location.root().resolve("mcp.json");
            case PROJECT, NESTED_PROJECT, EXPLICIT_PATH -> location.root().resolve(".ly-pi").resolve("mcp.json");
            default -> null;
        };
        if (direct != null && Files.isRegularFile(direct)) {
            files.add(direct);
        }
        Path directory = switch (location.layer()) {
            case USER -> location.root().resolve("mcp");
            case PROJECT, NESTED_PROJECT, EXPLICIT_PATH -> location.root().resolve(".ly-pi").resolve("mcp");
            default -> null;
        };
        if (directory != null && Files.isDirectory(directory)) {
            files.addAll(ResourceFiles.regularFiles(directory, diagnostics).stream()
                .filter(path -> path.getFileName().toString().endsWith(".json"))
                .toList());
        }
        return files;
    }

    private void readMcpConfig(
        ResourceLocation location,
        Path file,
        List<ResourceDiagnostic> diagnostics,
        Map<String, PrioritizedResource<McpServerConfig>> selected
    ) {
        try {
            JsonNode root = jsonMapper.readTree(file.toFile());
            JsonNode serversNode = serversNode(root);
            if (!serversNode.isObject()) {
                diagnostics.add(ResourceDiagnostics.warning("mcp config does not contain servers or mcpServers object", file));
                return;
            }
            serversNode.properties().forEach(entry ->
                mergeServer(entry.getKey(), toMcpServer(entry.getKey(), entry.getValue()), location, file, diagnostics, selected)
            );
        } catch (RuntimeException | IOException exception) {
            diagnostics.add(ResourceDiagnostics.warning("Failed to parse mcp config: " + exception.getMessage(), file));
        }
    }

    private JsonNode serversNode(JsonNode root) {
        JsonNode mcpServers = root.path("mcpServers");
        if (mcpServers.isObject()) {
            return mcpServers;
        }
        return root.path("servers");
    }

    private void mergeServer(
        String name,
        McpServerConfig server,
        ResourceLocation location,
        Path file,
        List<ResourceDiagnostic> diagnostics,
        Map<String, PrioritizedResource<McpServerConfig>> selected
    ) {
        PrioritizedResource<McpServerConfig> existing = selected.get(name);
        if (existing == null) {
            selected.put(name, new PrioritizedResource<>(server, location.priority(), location));
            return;
        }
        if (location.priority() >= existing.priority()) {
            diagnostics.add(ResourceDiagnostics.warning("mcp server override: " + name, file));
            selected.put(name, new PrioritizedResource<>(server, location.priority(), location));
        } else {
            diagnostics.add(ResourceDiagnostics.warning("mcp server shadowed by higher priority server: " + name, file));
        }
    }

    private McpServerConfig toMcpServer(String name, JsonNode node) {
        McpTransport transport = McpTransport.valueOf(node.path("transport").asText("STDIO").toUpperCase());
        List<String> command = new ArrayList<>();
        JsonNode commandNode = node.path("command");
        if (commandNode.isTextual()) {
            command.add(commandNode.asText());
        } else if (commandNode.isArray()) {
            commandNode.forEach(part -> command.add(part.asText()));
        }
        node.path("args").forEach(part -> command.add(part.asText()));
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
}
