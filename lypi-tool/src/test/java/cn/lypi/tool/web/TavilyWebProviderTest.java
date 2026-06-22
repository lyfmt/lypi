package cn.lypi.tool.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cn.lypi.contracts.web.WebFetchResponse;
import cn.lypi.contracts.web.WebSearchResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class TavilyWebProviderTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void mapsSearchRequestAndResponse() throws Exception {
        RecordingHttpTransport transport = new RecordingHttpTransport();
        transport.responseBody = """
            {
              "answer": "Tavily exposes a search endpoint.",
              "request_id": "req-1",
              "results": [
                {
                  "title": "Tavily Search",
                  "url": "https://docs.tavily.com/documentation/api-reference/endpoint/search",
                  "content": "Search API docs",
                  "raw_content": "# Search API",
                  "score": 0.9,
                  "published_date": "2026-06-22"
                }
              ]
            }
            """;
        TavilyWebProvider provider = provider(transport);

        WebSearchResponse response = provider.search(new WebSearchRequest(
            "tavily api",
            3,
            List.of("docs.tavily.com"),
            List.of("spam.example"),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            true
        ));

        JsonNode request = objectMapper.readTree(transport.requestBody);
        assertEquals("POST", transport.request.method());
        assertEquals("https://api.tavily.com/search", transport.request.uri().toString());
        assertEquals(Optional.of("Bearer test-key"), transport.request.headers().firstValue("Authorization"));
        assertEquals("tavily api", request.get("query").asText());
        assertEquals(3, request.get("max_results").asInt());
        assertTrue(request.get("include_answer").asBoolean());
        assertEquals("docs.tavily.com", request.get("include_domains").get(0).asText());
        assertEquals("spam.example", request.get("exclude_domains").get(0).asText());
        assertEquals("tavily", response.provider());
        assertEquals(Optional.of("Tavily exposes a search endpoint."), response.answer());
        assertEquals(1, response.results().size());
        assertEquals("Tavily Search", response.results().getFirst().title());
        assertEquals(Optional.of("Search API docs"), response.results().getFirst().snippet());
        assertEquals(Optional.of("# Search API"), response.results().getFirst().content());
        assertEquals(Optional.of("req-1"), response.usage().orElseThrow().requestId());
        assertFalse(response.toString().contains("test-key"));
    }

    @Test
    void mapsExtractRequestAndResponse() throws Exception {
        RecordingHttpTransport transport = new RecordingHttpTransport();
        transport.responseBody = """
            {
              "request_id": "extract-1",
              "results": [
                {
                  "url": "https://example.com/doc",
                  "raw_content": "# Example\\n\\nBody",
                  "title": "Example"
                }
              ]
            }
            """;
        TavilyWebProvider provider = provider(transport);

        WebFetchResponse response = provider.fetch(new WebFetchRequest(
            "https://example.com/doc",
            Optional.of("pricing"),
            "markdown",
            10_000,
            Optional.empty()
        ));

        JsonNode request = objectMapper.readTree(transport.requestBody);
        assertEquals("POST", transport.request.method());
        assertEquals("https://api.tavily.com/extract", transport.request.uri().toString());
        assertEquals("https://example.com/doc", request.get("urls").get(0).asText());
        assertTrue(request.path("include_images").isMissingNode() || !request.path("include_images").asBoolean());
        assertEquals("tavily", response.provider());
        assertEquals(Optional.of("Example"), response.title());
        assertEquals("# Example\n\nBody", response.content());
        assertEquals("markdown", response.format());
        assertEquals(Optional.of("extract-1"), response.usage().orElseThrow().requestId());
    }

    private TavilyWebProvider provider(RecordingHttpTransport transport) {
        return new TavilyWebProvider(
            new JavaHttpWebClient(transport, objectMapper, Duration.ofSeconds(5)),
            objectMapper,
            "test-key"
        );
    }
}
