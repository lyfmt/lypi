package cn.lypi.contracts.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class WebContractsTest {
    @Test
    void webSearchResponseCopiesResultLists() {
        List<WebSearchResult> results = new ArrayList<>();
        results.add(new WebSearchResult(
            "Tavily",
            "https://docs.tavily.com",
            Optional.of("Search API docs"),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.of(0.8d),
            Optional.empty()
        ));
        WebSearchResponse response = new WebSearchResponse(
            "tavily",
            "tavily search api",
            Optional.of("Tavily has a search endpoint."),
            results,
            Optional.of(new WebProviderUsage("tavily", Optional.of("req-1"), Map.of("credits", 1)))
        );

        results.clear();

        assertEquals(1, response.results().size());
        assertThrows(UnsupportedOperationException.class, () -> response.results().add(response.results().getFirst()));
    }

    @Test
    void providerUsageCopiesMetadataAndDoesNotStoreSecret() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("credits", 1);
        metadata.put("elapsedMs", 42);
        WebProviderUsage usage = new WebProviderUsage("tavily", Optional.of("req-1"), metadata);

        metadata.put("apiKey", "secret");

        assertEquals(1, usage.metadata().get("credits"));
        assertFalse(usage.metadata().containsKey("apiKey"));
        assertFalse(usage.metadata().containsKey("authorization"));
        assertFalse(usage.metadata().containsKey("Authorization"));
        assertThrows(UnsupportedOperationException.class, () -> usage.metadata().put("credits", 2));
    }

    @Test
    void fetchResponseCarriesMarkdownContent() {
        WebFetchResponse response = new WebFetchResponse(
            "tavily",
            "https://example.com/doc",
            Optional.of("Example"),
            "# Example\n\nBody",
            "markdown",
            Optional.of(Instant.parse("2026-06-22T00:00:00Z")),
            Optional.empty()
        );

        assertEquals("tavily", response.provider());
        assertEquals(Optional.of("Example"), response.title());
        assertTrue(response.content().contains("Body"));
        assertEquals("markdown", response.format());
    }
}
