package cn.lypi.tool.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import cn.lypi.contracts.web.WebProviderUsage;
import cn.lypi.contracts.web.WebSearchResponse;
import cn.lypi.contracts.web.WebSearchResult;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Exa MCP Web search provider。
 */
public final class ExaWebSearchProvider implements WebSearchProvider {
    public static final String DEFAULT_ENDPOINT = "https://mcp.exa.ai/mcp";
    private static final String TOOL_NAME = "web_search_exa";
    private static final int DEFAULT_CONTEXT_MAX_CHARACTERS = 12_000;

    private final JavaHttpWebClient client;
    private final ObjectMapper objectMapper;
    private final URI endpoint;

    public ExaWebSearchProvider(JavaHttpWebClient client, ObjectMapper objectMapper) {
        this(client, objectMapper, DEFAULT_ENDPOINT);
    }

    public ExaWebSearchProvider(JavaHttpWebClient client, ObjectMapper objectMapper, String endpoint) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.endpoint = endpoint(endpoint, DEFAULT_ENDPOINT);
    }

    @Override
    public String name() {
        return "exa";
    }

    @Override
    public WebSearchResponse search(WebSearchRequest request) {
        String responseText = client.postText(
            endpoint,
            Map.of("Accept", "application/json, text/event-stream"),
            mcpRequest(request).toString()
        );
        JsonNode response = parseResponsePayload(responseText);
        JsonNode payload = resultPayload(response);
        return new WebSearchResponse(
            name(),
            request.query(),
            Optional.empty(),
            results(payload),
            usage(payload, response)
        );
    }

    private ObjectNode mcpRequest(WebSearchRequest request) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("jsonrpc", "2.0");
        root.put("id", "lypi-1");
        root.put("method", "tools/call");

        ObjectNode params = root.putObject("params");
        params.put("name", TOOL_NAME);

        ObjectNode arguments = params.putObject("arguments");
        arguments.put("query", request.query());
        arguments.put("numResults", request.maxResults());
        arguments.put("livecrawl", "fallback");
        arguments.put("type", "auto");
        arguments.put("contextMaxCharacters", DEFAULT_CONTEXT_MAX_CHARACTERS);
        addArray(arguments, "includeDomains", request.allowedDomains());
        addArray(arguments, "excludeDomains", request.blockedDomains());
        return root;
    }

    private JsonNode parseResponsePayload(String rawText) {
        String payload = ssePayload(rawText).orElse(rawText);
        try {
            JsonNode response = objectMapper.readTree(payload == null || payload.isBlank() ? "{}" : payload);
            JsonNode error = response.path("error");
            if (!error.isMissingNode() && !error.isNull()) {
                throw new WebProviderException("Exa MCP 错误: " + WebJson.text(error, "message").orElse(error.toString()));
            }
            return response;
        } catch (IOException exception) {
            throw new WebProviderException("Exa MCP 响应解析失败: " + exception.getMessage(), exception);
        }
    }

    private Optional<String> ssePayload(String rawText) {
        if (rawText == null || !rawText.contains("data:")) {
            return Optional.empty();
        }
        String lastPayload = "";
        for (String line : rawText.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("data:")) {
                continue;
            }
            String data = trimmed.substring("data:".length()).trim();
            if (data.isBlank() || "[DONE]".equals(data)) {
                continue;
            }
            lastPayload = data;
        }
        return lastPayload.isBlank() ? Optional.empty() : Optional.of(lastPayload);
    }

    private JsonNode resultPayload(JsonNode response) {
        JsonNode result = response.path("result");
        JsonNode structuredResults = result.path("structuredContent").path("results");
        if (structuredResults.isArray()) {
            return result.path("structuredContent");
        }
        JsonNode directResultResults = result.path("results");
        if (directResultResults.isArray()) {
            return result;
        }
        JsonNode directResults = response.path("results");
        if (directResults.isArray()) {
            return response;
        }
        JsonNode content = result.path("content");
        if (content.isArray()) {
            for (JsonNode item : content) {
                Optional<String> text = WebJson.text(item, "text");
                if (text.isEmpty()) {
                    continue;
                }
                Optional<JsonNode> parsed = parseToolText(text.orElseThrow());
                if (parsed.isPresent()) {
                    return resultPayload(parsed.orElseThrow());
                }
            }
        }
        ObjectNode empty = objectMapper.createObjectNode();
        empty.putArray("results");
        return empty;
    }

    private Optional<JsonNode> parseToolText(String text) {
        String normalized = stripCodeFence(text);
        if (!normalized.startsWith("{") && !normalized.startsWith("[")) {
            return Optional.empty();
        }
        try {
            JsonNode parsed = objectMapper.readTree(normalized);
            if (parsed.isArray()) {
                ObjectNode root = objectMapper.createObjectNode();
                root.set("results", parsed);
                return Optional.of(root);
            }
            return Optional.of(parsed);
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    private String stripCodeFence(String text) {
        String normalized = text == null ? "" : text.trim();
        if (normalized.startsWith("```")) {
            int firstLineEnd = normalized.indexOf('\n');
            int lastFence = normalized.lastIndexOf("```");
            if (firstLineEnd >= 0 && lastFence > firstLineEnd) {
                normalized = normalized.substring(firstLineEnd + 1, lastFence).trim();
            }
        }
        return normalized;
    }

    private List<WebSearchResult> results(JsonNode payload) {
        JsonNode results = payload.path("results");
        if (!results.isArray()) {
            return List.of();
        }
        List<WebSearchResult> mapped = new ArrayList<>();
        for (JsonNode result : results) {
            mapped.add(new WebSearchResult(
                WebJson.text(result, "title").orElse(""),
                WebJson.text(result, "url").orElse(""),
                WebJson.text(result, "summary").or(() -> WebJson.text(result, "snippet")),
                WebJson.text(result, "text").or(() -> WebJson.text(result, "content")),
                WebJson.instant(result, "publishedDate").or(() -> WebJson.instant(result, "published_date")),
                WebJson.instant(result, "lastUpdated").or(() -> WebJson.instant(result, "last_updated")),
                WebJson.decimal(result, "score"),
                WebJson.text(result, "favicon")
            ));
        }
        return List.copyOf(mapped);
    }

    private Optional<WebProviderUsage> usage(JsonNode payload, JsonNode response) {
        Optional<String> requestId = WebJson.text(payload, "requestId").or(() -> WebJson.text(response, "id"));
        return Optional.of(new WebProviderUsage(name(), requestId, Map.of()));
    }

    private void addArray(ObjectNode body, String fieldName, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        ArrayNode array = body.putArray(fieldName);
        values.forEach(array::add);
    }

    private static URI endpoint(String endpoint, String defaultEndpoint) {
        String value = endpoint == null || endpoint.isBlank() ? defaultEndpoint : endpoint.trim();
        return URI.create(value);
    }
}
