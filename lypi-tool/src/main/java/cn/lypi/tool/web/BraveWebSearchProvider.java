package cn.lypi.tool.web;

import com.fasterxml.jackson.databind.JsonNode;
import cn.lypi.contracts.web.WebSearchResponse;
import cn.lypi.contracts.web.WebSearchResult;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Brave Web Search provider。
 */
public final class BraveWebSearchProvider implements WebSearchProvider {
    private static final String DEFAULT_ENDPOINT = "https://api.search.brave.com";

    private final JavaHttpWebClient client;
    private final String apiKey;
    private final URI endpoint;

    public BraveWebSearchProvider(JavaHttpWebClient client, String apiKey) {
        this(client, apiKey, DEFAULT_ENDPOINT);
    }

    public BraveWebSearchProvider(JavaHttpWebClient client, String apiKey, String endpoint) {
        this.client = client;
        this.apiKey = apiKey;
        this.endpoint = endpoint(endpoint, DEFAULT_ENDPOINT);
    }

    @Override
    public String name() {
        return "brave";
    }

    @Override
    public WebSearchResponse search(WebSearchRequest request) {
        JsonNode response = client.get(searchUri(request), Map.of(
            "Accept", "application/json",
            "X-Subscription-Token", apiKey
        ));
        return new WebSearchResponse(
            name(),
            request.query(),
            Optional.empty(),
            results(response.path("web").path("results")),
            Optional.empty()
        );
    }

    private URI searchUri(WebSearchRequest request) {
        StringBuilder query = new StringBuilder();
        query.append("q=").append(encode(request.query()));
        query.append("&count=").append(request.maxResults());
        request.recency().map(this::freshness).ifPresent(freshness ->
            query.append("&freshness=").append(encode(freshness))
        );
        return endpoint.resolve("res/v1/web/search?" + query);
    }

    private String freshness(String recency) {
        return switch (recency) {
            case "hour", "day" -> "pd";
            case "week" -> "pw";
            case "month" -> "pm";
            case "year" -> "py";
            default -> recency;
        };
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
                WebJson.text(result, "description"),
                Optional.empty(),
                WebJson.instant(result, "page_age"),
                Optional.empty(),
                Optional.empty(),
                WebJson.text(result, "profile").or(() -> WebJson.text(result, "thumbnail"))
            ));
        }
        return mapped;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("%20", "+");
    }

    private static URI endpoint(String endpoint, String defaultEndpoint) {
        String value = endpoint == null || endpoint.isBlank() ? defaultEndpoint : endpoint.trim();
        if (!value.endsWith("/")) {
            value = value + "/";
        }
        return URI.create(value);
    }
}
