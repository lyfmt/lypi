package cn.lypi.tool.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import cn.lypi.contracts.web.WebSearchResponse;
import cn.lypi.contracts.web.WebSearchResult;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Perplexity Search API provider。
 */
public final class PerplexityWebSearchProvider implements WebSearchProvider {
    private static final String DEFAULT_ENDPOINT = "https://api.perplexity.ai";

    private final JavaHttpWebClient client;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final URI searchUri;

    public PerplexityWebSearchProvider(JavaHttpWebClient client, ObjectMapper objectMapper, String apiKey) {
        this(client, objectMapper, apiKey, DEFAULT_ENDPOINT);
    }

    public PerplexityWebSearchProvider(JavaHttpWebClient client, ObjectMapper objectMapper, String apiKey, String endpoint) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.searchUri = endpoint(endpoint, DEFAULT_ENDPOINT).resolve("search");
    }

    @Override
    public String name() {
        return "perplexity";
    }

    @Override
    public WebSearchResponse search(WebSearchRequest request) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("query", request.query());
        body.put("max_results", request.maxResults());
        addArray(body, "search_domain_filter", request.allowedDomains());
        request.recency().ifPresent(recency -> body.put("search_recency_filter", recency));

        JsonNode response = client.postJson(searchUri, Map.of("Authorization", "Bearer " + apiKey), body);
        return new WebSearchResponse(
            name(),
            request.query(),
            Optional.empty(),
            results(response.path("results")),
            Optional.empty()
        );
    }

    private List<WebSearchResult> results(JsonNode results) {
        if (!results.isArray()) {
            return List.of();
        }
        List<WebSearchResult> mapped = new ArrayList<>();
        for (JsonNode result : results) {
            mapped.add(new WebSearchResult(
                WebJson.text(result, "title").orElse(""),
                WebJson.text(result, "url").orElse(""),
                WebJson.text(result, "snippet"),
                Optional.empty(),
                WebJson.instant(result, "date"),
                WebJson.instant(result, "last_updated"),
                Optional.empty(),
                Optional.empty()
            ));
        }
        return mapped;
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
        if (!value.endsWith("/")) {
            value = value + "/";
        }
        return URI.create(value);
    }
}
