package cn.lypi.tool.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import cn.lypi.contracts.web.WebProviderUsage;
import cn.lypi.contracts.web.WebSearchResponse;
import cn.lypi.contracts.web.WebSearchResult;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Tavily Search provider。
 */
public final class TavilyWebProvider implements WebSearchProvider {
    private static final String DEFAULT_ENDPOINT = "https://api.tavily.com";

    private final JavaHttpWebClient client;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final URI searchUri;

    public TavilyWebProvider(JavaHttpWebClient client, ObjectMapper objectMapper, String apiKey) {
        this(client, objectMapper, apiKey, DEFAULT_ENDPOINT);
    }

    public TavilyWebProvider(JavaHttpWebClient client, ObjectMapper objectMapper, String apiKey, String endpoint) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        URI baseUri = endpoint(endpoint, DEFAULT_ENDPOINT);
        this.searchUri = baseUri.resolve("search");
    }

    @Override
    public String name() {
        return "tavily";
    }

    @Override
    public WebSearchResponse search(WebSearchRequest request) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("query", request.query());
        body.put("max_results", request.maxResults());
        body.put("include_answer", request.includeAnswer());
        body.put("include_raw_content", true);
        addArray(body, "include_domains", request.allowedDomains());
        addArray(body, "exclude_domains", request.blockedDomains());

        JsonNode response = client.postJson(searchUri, authHeaders(), body);
        return new WebSearchResponse(
            name(),
            request.query(),
            WebJson.text(response, "answer"),
            searchResults(response.path("results")),
            usage(response)
        );
    }

    private List<WebSearchResult> searchResults(JsonNode results) {
        if (!results.isArray()) {
            return List.of();
        }
        List<WebSearchResult> mapped = new ArrayList<>();
        for (JsonNode result : results) {
            String title = WebJson.text(result, "title").orElse("");
            String url = WebJson.text(result, "url").orElse("");
            mapped.add(new WebSearchResult(
                title,
                url,
                WebJson.text(result, "content"),
                WebJson.text(result, "raw_content"),
                WebJson.instant(result, "published_date"),
                Optional.empty(),
                WebJson.decimal(result, "score"),
                Optional.empty()
            ));
        }
        return mapped;
    }

    private Optional<WebProviderUsage> usage(JsonNode response) {
        return Optional.of(new WebProviderUsage(
            name(),
            WebJson.text(response, "request_id"),
            Map.of()
        ));
    }

    private Map<String, String> authHeaders() {
        return Map.of("Authorization", "Bearer " + apiKey);
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
