package cn.lypi.tool.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import cn.lypi.contracts.web.WebFetchResponse;
import cn.lypi.contracts.web.WebProviderUsage;
import cn.lypi.contracts.web.WebSearchResponse;
import cn.lypi.contracts.web.WebSearchResult;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Tavily Search 和 Extract provider。
 */
public final class TavilyWebProvider implements WebSearchProvider, WebFetchProvider {
    private static final URI SEARCH_URI = URI.create("https://api.tavily.com/search");
    private static final URI EXTRACT_URI = URI.create("https://api.tavily.com/extract");

    private final JavaHttpWebClient client;
    private final ObjectMapper objectMapper;
    private final String apiKey;

    public TavilyWebProvider(JavaHttpWebClient client, ObjectMapper objectMapper, String apiKey) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
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

        JsonNode response = client.postJson(SEARCH_URI, authHeaders(), body);
        return new WebSearchResponse(
            name(),
            request.query(),
            WebJson.text(response, "answer"),
            searchResults(response.path("results")),
            usage(response)
        );
    }

    @Override
    public WebFetchResponse fetch(WebFetchRequest request) {
        ObjectNode body = objectMapper.createObjectNode();
        ArrayNode urls = body.putArray("urls");
        urls.add(request.url());
        body.put("extract_depth", "basic");
        body.put("format", request.format());

        JsonNode response = client.postJson(EXTRACT_URI, authHeaders(), body);
        JsonNode result = firstResult(response.path("results"));
        String content = WebJson.text(result, "raw_content")
            .or(() -> WebJson.text(result, "content"))
            .orElse("");
        if (content.length() > request.maxChars()) {
            content = content.substring(0, request.maxChars());
        }
        return new WebFetchResponse(
            name(),
            request.url(),
            WebJson.text(result, "title"),
            content,
            request.format(),
            Optional.of(Instant.now()),
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

    private JsonNode firstResult(JsonNode results) {
        return results.isArray() && !results.isEmpty() ? results.get(0) : objectMapper.createObjectNode();
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
}
