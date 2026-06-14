package cn.lypi.resource;

import cn.lypi.contracts.mcp.McpServerConfig;
import cn.lypi.contracts.mcp.McpBearerAuthConfig;
import cn.lypi.contracts.mcp.McpAuthConfig;
import cn.lypi.contracts.mcp.McpHttpServerConfig;
import cn.lypi.contracts.mcp.McpNoAuthConfig;
import cn.lypi.contracts.mcp.McpStdioServerConfig;
import cn.lypi.contracts.mcp.McpTransport;
import cn.lypi.contracts.resource.ResourceDiagnostic;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 扫描 MCP Server 静态配置。
 *
 * NOTE: 该扫描器只做配置解析和静态校验，不建立 MCP 连接。
 */
class McpConfigScanner {
    private final ObjectMapper jsonMapper = new ObjectMapper();

    /**
     * 从资源位置中扫描 MCP 配置文件。
     */
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
            serversNode.properties().forEach(entry -> {
                McpServerConfig server = toMcpServer(entry.getKey(), entry.getValue(), file, diagnostics);
                if (server != null) {
                    mergeServer(entry.getKey(), server, location, file, diagnostics, selected);
                }
            });
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

    private McpServerConfig toMcpServer(
        String name,
        JsonNode node,
        Path file,
        List<ResourceDiagnostic> diagnostics
    ) {
        McpTransport transport = parseTransport(name, node, file, diagnostics);
        if (transport == null) {
            return null;
        }
        McpStdioServerConfig stdio = transport == McpTransport.STDIO ? stdioConfig(name, node, file, diagnostics) : null;
        McpHttpServerConfig http = transport == McpTransport.HTTP ? httpConfig(name, node, file, diagnostics) : null;
        return new McpServerConfig(
            name,
            transport,
            stdio,
            http,
            Duration.ofSeconds(timeoutSeconds(name, node, "startupTimeoutSeconds", 10, file, diagnostics)),
            Duration.ofSeconds(timeoutSeconds(name, node, "callTimeoutSeconds", 60, file, diagnostics))
        );
    }

    private McpStdioServerConfig stdioConfig(
        String name,
        JsonNode node,
        Path file,
        List<ResourceDiagnostic> diagnostics
    ) {
        List<String> command = new ArrayList<>();
        JsonNode commandNode = node.path("command");
        if (commandNode.isTextual()) {
            command.add(commandNode.asText());
        } else if (commandNode.isArray()) {
            commandNode.forEach(part -> command.add(part.asText()));
        }
        node.path("args").forEach(part -> command.add(part.asText()));
        if (command.isEmpty() || command.getFirst().isBlank()) {
            diagnostics.add(ResourceDiagnostics.warning("mcp server command is empty: " + name, file));
        }
        Map<String, String> env = stringMap(node.path("env"));
        return new McpStdioServerConfig(List.copyOf(command), Map.copyOf(env));
    }

    private McpHttpServerConfig httpConfig(
        String name,
        JsonNode node,
        Path file,
        List<ResourceDiagnostic> diagnostics
    ) {
        URI url = parseUri(name, node.path("url").asText(""), file, diagnostics);
        return new McpHttpServerConfig(
            url,
            Map.copyOf(stringMap(node.path("headers"))),
            authConfig(name, node.path("auth"), file, diagnostics),
            Duration.ofSeconds(timeoutSeconds(name, node, "connectTimeoutSeconds", 10, file, diagnostics)),
            Duration.ofSeconds(timeoutSeconds(name, node, "readTimeoutSeconds", 60, file, diagnostics))
        );
    }

    private URI parseUri(String name, String value, Path file, List<ResourceDiagnostic> diagnostics) {
        if (value == null || value.isBlank()) {
            diagnostics.add(ResourceDiagnostics.warning("HTTP mcp server url is empty: " + name, file));
            return null;
        }
        try {
            return new URI(value);
        } catch (URISyntaxException exception) {
            diagnostics.add(ResourceDiagnostics.warning("HTTP mcp server url is invalid for " + name + ": " + exception.getMessage(), file));
            return null;
        }
    }

    private McpAuthConfig authConfig(String name, JsonNode node, Path file, List<ResourceDiagnostic> diagnostics) {
        if (!node.isObject()) {
            return new McpNoAuthConfig();
        }
        String type = node.path("type").asText("none").toLowerCase(Locale.ROOT);
        return switch (type) {
            case "none" -> new McpNoAuthConfig();
            case "bearer" -> new McpBearerAuthConfig(
                node.path("tokenEnv").asText(""),
                node.path("token").asText("")
            );
            default -> {
                diagnostics.add(ResourceDiagnostics.warning("unsupported mcp auth type for " + name + ": " + type, file));
                yield new McpNoAuthConfig();
            }
        };
    }

    private Map<String, String> stringMap(JsonNode node) {
        Map<String, String> values = new LinkedHashMap<>();
        if (node.isObject()) {
            node.properties().forEach(entry -> values.put(entry.getKey(), entry.getValue().asText()));
        }
        return values;
    }

    private McpTransport parseTransport(
        String name,
        JsonNode node,
        Path file,
        List<ResourceDiagnostic> diagnostics
    ) {
        String transportName = node.path("transport").asText("STDIO").toUpperCase(Locale.ROOT);
        try {
            return McpTransport.valueOf(transportName);
        } catch (IllegalArgumentException exception) {
            diagnostics.add(ResourceDiagnostics.warning("unsupported mcp transport for " + name + ": " + transportName, file));
            return null;
        }
    }

    private long timeoutSeconds(
        String name,
        JsonNode node,
        String fieldName,
        long defaultValue,
        Path file,
        List<ResourceDiagnostic> diagnostics
    ) {
        JsonNode value = node.path(fieldName);
        if (value.isMissingNode()) {
            return defaultValue;
        }
        long seconds = value.asLong(defaultValue);
        if (seconds <= 0) {
            diagnostics.add(ResourceDiagnostics.warning(
                "mcp server " + fieldName + " must be positive for " + name + "; using default " + defaultValue,
                file
            ));
            return defaultValue;
        }
        return seconds;
    }
}
