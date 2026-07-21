package cn.lypi.tool.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.ObjectMapper;
import cn.lypi.contracts.web.WebSearchResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class BraveWebSearchProviderTest {
    @Test
    void mapsWebSearchRequestAndResponse() {
        ObjectMapper objectMapper = new ObjectMapper();
        RecordingHttpTransport transport = new RecordingHttpTransport();
        transport.responseBody = """
            {
              "web": {
                "results": [
                  {
                    "title": "Brave Search API",
                    "url": "https://api-dashboard.search.brave.com/app/documentation/web-search/get-started",
                    "description": "Web search docs",
                    "page_age": "2026-06-22"
                  }
                ]
              }
            }
            """;
        BraveWebSearchProvider provider = new BraveWebSearchProvider(
            new JavaHttpWebClient(transport, objectMapper, Duration.ofSeconds(5)),
            "brave-key"
        );

        WebSearchResponse response = provider.search(new WebSearchRequest(
            "brave api",
            2,
            List.of(),
            List.of(),
            Optional.of("week"),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            false
        ));

        assertEquals("GET", transport.request.method());
        assertEquals(Optional.of("brave-key"), transport.request.headers().firstValue("X-Subscription-Token"));
        assertEquals("/res/v1/web/search", transport.request.uri().getPath());
        assertEquals("q=brave+api&count=2&freshness=pw", transport.request.uri().getRawQuery());
        assertEquals("brave", response.provider());
        assertEquals(1, response.results().size());
        assertEquals("Brave Search API", response.results().getFirst().title());
        assertEquals(Optional.of("Web search docs"), response.results().getFirst().snippet());
        assertFalse(response.toString().contains("brave-key"));
    }

    @Test
    void usesConfiguredEndpoint() {
        ObjectMapper objectMapper = new ObjectMapper();
        RecordingHttpTransport transport = new RecordingHttpTransport();
        BraveWebSearchProvider provider = new BraveWebSearchProvider(
            new JavaHttpWebClient(transport, objectMapper, Duration.ofSeconds(5)),
            "brave-key",
            "https://brave.internal/api"
        );

        provider.search(new WebSearchRequest(
            "brave api",
            2,
            List.of(),
            List.of(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            false
        ));

        assertEquals("https://brave.internal/api/res/v1/web/search?q=brave+api&count=2", transport.request.uri().toString());
    }
}
