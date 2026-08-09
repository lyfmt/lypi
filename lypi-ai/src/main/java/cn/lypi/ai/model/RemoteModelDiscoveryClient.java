package cn.lypi.ai.model;

import cn.lypi.contracts.error.ErrorSeverity;
import cn.lypi.contracts.error.ModelProviderException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class RemoteModelDiscoveryClient {
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public RemoteModelDiscoveryClient() {
        this(HttpClient.newHttpClient(), new ObjectMapper());
    }

    public RemoteModelDiscoveryClient(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public List<String> discover(URI baseUrl, String apiKey, List<String> paths, Duration timeout) {
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(paths, "paths");
        Duration requestTimeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        List<String> diagnostics = new ArrayList<>();
        for (String path : paths) {
            URI endpoint = endpoint(baseUrl, path);
            DiscoveryAttempt attempt = request(endpoint, apiKey, requestTimeout);
            if (!attempt.modelIds().isEmpty()) {
                return attempt.modelIds().stream().distinct().toList();
            }
            diagnostics.add(safeEndpoint(endpoint) + ": " + attempt.diagnostic());
            if (attempt.interrupted()) {
                break;
            }
        }
        String details = diagnostics.isEmpty()
            ? "no candidate endpoints configured"
            : String.join("; ", diagnostics);
        throw new ModelProviderException(
            "model.discovery_unavailable",
            ErrorSeverity.ERROR,
            false,
            "Remote model discovery returned no usable models. " + details
        );
    }

    private DiscoveryAttempt request(URI endpoint, String apiKey, Duration timeout) {
        HttpRequest request;
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .GET();
            if (apiKey != null && !apiKey.isBlank()) {
                builder.header("Authorization", "Bearer " + apiKey);
            }
            request = builder.build();
        } catch (RuntimeException error) {
            return DiscoveryAttempt.failure("invalid request configuration");
        }

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return DiscoveryAttempt.interruptedFailure();
        } catch (IOException error) {
            return DiscoveryAttempt.failure("network error: " + error.getClass().getSimpleName());
        } catch (RuntimeException error) {
            return DiscoveryAttempt.failure("request error: " + error.getClass().getSimpleName());
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return DiscoveryAttempt.failure("HTTP " + response.statusCode());
        }
        try {
            List<String> modelIds = parse(response.body());
            if (modelIds.isEmpty()) {
                return DiscoveryAttempt.failure("response contained no usable model ids");
            }
            return DiscoveryAttempt.success(modelIds);
        } catch (IOException | RuntimeException error) {
            return DiscoveryAttempt.failure("invalid JSON response");
        }
    }

    private List<String> parse(String body) throws IOException {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        JsonNode root = objectMapper.readTree(body);
        if (root.isArray()) {
            return stringArray(root);
        }
        List<String> dataModels = objectArrayIds(root.path("data"));
        if (!dataModels.isEmpty()) {
            return dataModels;
        }
        return objectArrayIds(root.path("models"));
    }

    private static List<String> stringArray(JsonNode node) {
        List<String> modelIds = new ArrayList<>();
        for (JsonNode item : node) {
            if (item.isTextual() && !item.asText().isBlank()) {
                modelIds.add(item.asText());
            }
        }
        return List.copyOf(modelIds);
    }

    private static List<String> objectArrayIds(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<String> modelIds = new ArrayList<>();
        for (JsonNode item : node) {
            JsonNode id = item.path("id");
            if (id.isTextual() && !id.asText().isBlank()) {
                modelIds.add(id.asText());
            }
        }
        return List.copyOf(modelIds);
    }

    private static URI endpoint(URI baseUrl, String path) {
        String base = baseUrl.toString();
        String normalizedBase = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        String normalizedPath = path == null ? "" : path;
        normalizedPath = normalizedPath.startsWith("/") ? normalizedPath.substring(1) : normalizedPath;
        return URI.create(normalizedBase + "/" + normalizedPath);
    }

    private static String safeEndpoint(URI endpoint) {
        String path = endpoint.getRawPath() == null || endpoint.getRawPath().isBlank() ? "/" : endpoint.getRawPath();
        if (endpoint.getHost() == null) {
            return path;
        }
        String port = endpoint.getPort() < 0 ? "" : ":" + endpoint.getPort();
        return endpoint.getScheme() + "://" + endpoint.getHost() + port + path;
    }

    private record DiscoveryAttempt(List<String> modelIds, String diagnostic, boolean interrupted) {
        private DiscoveryAttempt {
            modelIds = List.copyOf(modelIds);
            diagnostic = diagnostic == null ? "unknown failure" : diagnostic;
        }

        private static DiscoveryAttempt success(List<String> modelIds) {
            return new DiscoveryAttempt(modelIds, "", false);
        }

        private static DiscoveryAttempt failure(String diagnostic) {
            return new DiscoveryAttempt(List.of(), diagnostic, false);
        }

        private static DiscoveryAttempt interruptedFailure() {
            return new DiscoveryAttempt(List.of(), "request interrupted", true);
        }
    }
}
