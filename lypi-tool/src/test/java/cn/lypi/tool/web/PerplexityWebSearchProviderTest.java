package cn.lypi.tool.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cn.lypi.contracts.web.WebSearchResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class PerplexityWebSearchProviderTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void mapsSearchRequestAndResponse() throws Exception {
        RecordingHttpTransport transport = new RecordingHttpTransport();
        transport.responseBody = """
            {
              "results": [
                {
                  "title": "Perplexity Search",
                  "url": "https://docs.perplexity.ai/guides/search-guide",
                  "snippet": "Search API guide",
                  "date": "2026-06-22",
                  "last_updated": "2026-06-22T10:00:00Z"
                }
              ]
            }
            """;
        PerplexityWebSearchProvider provider = new PerplexityWebSearchProvider(
            new JavaHttpWebClient(transport, objectMapper, Duration.ofSeconds(5)),
            objectMapper,
            "ppl-key"
        );

        WebSearchResponse response = provider.search(new WebSearchRequest(
            "perplexity search api",
            4,
            List.of("docs.perplexity.ai"),
            List.of(),
            Optional.of("month"),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            false
        ));

        JsonNode request = objectMapper.readTree(transport.requestBody);
        assertEquals("POST", transport.request.method());
        assertEquals("https://api.perplexity.ai/search", transport.request.uri().toString());
        assertEquals(Optional.of("Bearer ppl-key"), transport.request.headers().firstValue("Authorization"));
        assertEquals("perplexity search api", request.get("query").asText());
        assertEquals(4, request.get("max_results").asInt());
        assertEquals("docs.perplexity.ai", request.get("search_domain_filter").get(0).asText());
        assertEquals("month", request.get("search_recency_filter").asText());
        assertEquals("perplexity", response.provider());
        assertEquals(1, response.results().size());
        assertEquals("Perplexity Search", response.results().getFirst().title());
        assertEquals(Optional.of("Search API guide"), response.results().getFirst().snippet());
        assertFalse(response.toString().contains("ppl-key"));
    }
}
