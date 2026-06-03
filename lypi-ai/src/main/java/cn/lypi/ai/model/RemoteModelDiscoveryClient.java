package cn.lypi.ai.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class RemoteModelDiscoveryClient {
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private volatile String lastFailure;

    public RemoteModelDiscoveryClient() {
        this(HttpClient.newHttpClient(), new ObjectMapper());
    }

    public RemoteModelDiscoveryClient(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public Optional<String> lastFailure() {
        return Optional.ofNullable(lastFailure);
    }

    public List<String> discover(URI baseUrl, String apiKey, List<String> paths, Duration timeout) {
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(paths, "paths");
        lastFailure = null;
        Duration requestTimeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        for (String path : paths) {
            List<String> modelIds = request(baseUrl, apiKey, path, requestTimeout);
            if (!modelIds.isEmpty()) {
                return modelIds;
            }
        }
        return List.of();
    }

    private List<String> request(URI baseUrl, String apiKey, String path, Duration timeout) {
        URI endpoint = endpoint(baseUrl, path);
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
            .timeout(timeout)
            .GET();
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        try {
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                recordFailure("Discovery " + endpoint + " returned HTTP " + response.statusCode() + ".");
                return List.of();
            }
            return parse(response.body());
        } catch (IOException | InterruptedException | RuntimeException error) {
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            recordFailure("Discovery " + endpoint + " " + failureKind(error) + ": " + failureMessage(error) + ".");
            return List.of();
        }
    }

    private void recordFailure(String message) {
        lastFailure = message;
    }

    private static String failureMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return error.getClass().getSimpleName();
        }
        return message;
    }

    private static String failureKind(Throwable error) {
        if (error instanceof UncheckedIOException || error instanceof IOException) {
            return "parse or network failed";
        }
        return "failed";
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
}
