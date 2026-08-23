package cn.lycode.ai.model;

import cn.lycode.contracts.error.ErrorSeverity;
import cn.lycode.contracts.error.ModelProviderException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

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
        return discoverModels(baseUrl, apiKey, paths, timeout).stream()
            .map(DiscoveredModel::modelId)
            .toList();
    }

    public List<DiscoveredModel> discoverModels(
        URI baseUrl,
        String apiKey,
        List<String> paths,
        Duration timeout
    ) {
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(paths, "paths");
        Duration requestTimeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        List<String> diagnostics = new ArrayList<>();
        for (String path : paths) {
            URI endpoint = endpoint(baseUrl, path);
            DiscoveryAttempt attempt = request(endpoint, apiKey, requestTimeout);
            if (!attempt.models().isEmpty()) {
                return attempt.models();
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
            List<DiscoveredModel> models = parse(response.body());
            if (models.isEmpty()) {
                return DiscoveryAttempt.failure("response contained no usable model ids");
            }
            return DiscoveryAttempt.success(models);
        } catch (IOException | RuntimeException error) {
            return DiscoveryAttempt.failure("invalid JSON response");
        }
    }

    private List<DiscoveredModel> parse(String body) throws IOException {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        JsonNode root = objectMapper.readTree(body);
        if (root.isArray()) {
            return stringArray(root);
        }
        List<DiscoveredModel> dataModels = objectArrayModels(root.path("data"));
        if (!dataModels.isEmpty()) {
            return dataModels;
        }
        return objectArrayModels(root.path("models"));
    }

    private static List<DiscoveredModel> stringArray(JsonNode node) {
        Map<String, DiscoveredModel> models = new LinkedHashMap<>();
        for (JsonNode item : node) {
            if (item.isTextual() && !item.asText().isBlank()) {
                models.putIfAbsent(item.asText(), DiscoveredModel.idOnly(item.asText()));
            }
        }
        return List.copyOf(models.values());
    }

    private static List<DiscoveredModel> objectArrayModels(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        Map<String, DiscoveredModel> models = new LinkedHashMap<>();
        for (JsonNode item : node) {
            JsonNode id = item.path("id");
            if (id.isTextual() && !id.asText().isBlank()) {
                models.putIfAbsent(id.asText(), discoveredModel(id.asText(), item));
            }
        }
        return List.copyOf(models.values());
    }

    private static DiscoveredModel discoveredModel(String modelId, JsonNode item) {
        Optional<Boolean> supportsThinking = firstBoolean(
            item.path("supportsThinking"),
            item.path("supports_thinking"),
            item.path("supportsReasoning"),
            item.path("supports_reasoning")
        );
        if (supportsThinking.isEmpty() && supportsReasoningParameter(item.path("supported_parameters"))) {
            supportsThinking = Optional.of(true);
        }

        Optional<Boolean> supportsImageInput = firstBoolean(
            item.path("supportsImageInput"),
            item.path("supports_image_input")
        );
        if (supportsImageInput.isEmpty()) {
            supportsImageInput = firstImageCapability(
                item.path("input_modalities"),
                item.path("architecture").path("input_modalities")
            );
        }

        return new DiscoveredModel(
            modelId,
            firstPositiveInt(
                item.path("contextWindow"),
                item.path("context_length"),
                item.path("context_window")
            ),
            firstPositiveInt(
                item.path("maxOutputTokens"),
                item.path("max_output_tokens"),
                item.path("max_tokens"),
                item.path("top_provider").path("max_completion_tokens")
            ),
            supportsThinking,
            supportsImageInput
        );
    }

    private static OptionalInt firstPositiveInt(JsonNode... candidates) {
        for (JsonNode candidate : candidates) {
            if (candidate.isIntegralNumber() && candidate.canConvertToInt() && candidate.intValue() > 0) {
                return OptionalInt.of(candidate.intValue());
            }
        }
        return OptionalInt.empty();
    }

    private static Optional<Boolean> firstBoolean(JsonNode... candidates) {
        for (JsonNode candidate : candidates) {
            if (candidate.isBoolean()) {
                return Optional.of(candidate.booleanValue());
            }
        }
        return Optional.empty();
    }

    private static boolean supportsReasoningParameter(JsonNode parameters) {
        if (!parameters.isArray()) {
            return false;
        }
        for (JsonNode parameter : parameters) {
            if (!parameter.isTextual()) {
                continue;
            }
            String value = parameter.asText();
            if ("reasoning".equals(value) || "reasoning_effort".equals(value) || "thinking".equals(value)) {
                return true;
            }
        }
        return false;
    }

    private static Optional<Boolean> firstImageCapability(JsonNode... modalitiesCandidates) {
        for (JsonNode modalities : modalitiesCandidates) {
            if (!modalities.isArray() || modalities.isEmpty()) {
                continue;
            }
            boolean onlyText = true;
            for (JsonNode modality : modalities) {
                if (modality.isTextual() && "image".equals(modality.asText())) {
                    return Optional.of(true);
                }
                if (!modality.isTextual() || !"text".equals(modality.asText())) {
                    onlyText = false;
                }
            }
            if (onlyText) {
                return Optional.of(false);
            }
        }
        return Optional.empty();
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

    private record DiscoveryAttempt(List<DiscoveredModel> models, String diagnostic, boolean interrupted) {
        private DiscoveryAttempt {
            models = List.copyOf(models);
            diagnostic = diagnostic == null ? "unknown failure" : diagnostic;
        }

        private static DiscoveryAttempt success(List<DiscoveredModel> models) {
            return new DiscoveryAttempt(models, "", false);
        }

        private static DiscoveryAttempt failure(String diagnostic) {
            return new DiscoveryAttempt(List.of(), diagnostic, false);
        }

        private static DiscoveryAttempt interruptedFailure() {
            return new DiscoveryAttempt(List.of(), "request interrupted", true);
        }
    }
}
