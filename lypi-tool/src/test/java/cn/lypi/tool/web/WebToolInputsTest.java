package cn.lypi.tool.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class WebToolInputsTest {
    @Test
    void parsesSearchInputWithDefaults() {
        WebSearchRequest request = WebToolInputs.search(Map.of("query", " tavily api "));

        assertEquals("tavily api", request.query());
        assertEquals(5, request.maxResults());
        assertEquals(List.of(), request.allowedDomains());
        assertEquals(List.of(), request.blockedDomains());
        assertEquals(Optional.empty(), request.recency());
        assertEquals(Optional.empty(), request.country());
        assertEquals(Optional.empty(), request.language());
        assertEquals(Optional.empty(), request.provider());
        assertEquals(false, request.includeAnswer());
    }

    @Test
    void parsesSearchInputWithLimitsAndDomainNormalization() {
        WebSearchRequest request = WebToolInputs.search(Map.of(
            "query", "java",
            "maxResults", 100,
            "allowedDomains", List.of(" Docs.Example.com ", "", "docs.example.com"),
            "blockedDomains", List.of("spam.example", " SPAM.example "),
            "recency", "week",
            "country", "US",
            "language", "en",
            "provider", "TAVILY",
            "includeAnswer", true
        ));

        assertEquals(10, request.maxResults());
        assertEquals(List.of("docs.example.com"), request.allowedDomains());
        assertEquals(List.of("spam.example"), request.blockedDomains());
        assertEquals(Optional.of("week"), request.recency());
        assertEquals(Optional.of("US"), request.country());
        assertEquals(Optional.of("en"), request.language());
        assertEquals(Optional.of("tavily"), request.provider());
        assertTrue(request.includeAnswer());
    }

    @Test
    void rejectsInvalidSearchInput() {
        IllegalArgumentException blankQuery = assertThrows(
            IllegalArgumentException.class,
            () -> WebToolInputs.search(Map.of("query", " "))
        );
        IllegalArgumentException provider = assertThrows(
            IllegalArgumentException.class,
            () -> WebToolInputs.search(Map.of("query", "java", "provider", "unknown"))
        );
        IllegalArgumentException domains = assertThrows(
            IllegalArgumentException.class,
            () -> WebToolInputs.search(Map.of("query", "java", "allowedDomains", List.of(1)))
        );

        assertTrue(blankQuery.getMessage().contains("query"));
        assertTrue(provider.getMessage().contains("provider"));
        assertTrue(domains.getMessage().contains("allowedDomains"));
    }

    @Test
    void parsesFetchInput() {
        WebFetchRequest request = WebToolInputs.fetch(Map.of(
            "url", " https://example.com/doc ",
            "query", " pricing ",
            "format", "text",
            "maxChars", 100_000
        ));

        assertEquals("https://example.com/doc", request.url());
        assertEquals(Optional.of("pricing"), request.query());
        assertEquals("text", request.format());
        assertEquals(50_000, request.maxChars());
    }

    @Test
    void rejectsInvalidFetchInput() {
        IllegalArgumentException blankUrl = assertThrows(
            IllegalArgumentException.class,
            () -> WebToolInputs.fetch(Map.of("url", " "))
        );
        IllegalArgumentException format = assertThrows(
            IllegalArgumentException.class,
            () -> WebToolInputs.fetch(Map.of("url", "https://example.com", "format", "html"))
        );
        IllegalArgumentException provider = assertThrows(
            IllegalArgumentException.class,
            () -> WebToolInputs.fetch(Map.of("url", "https://example.com", "provider", "tavily"))
        );

        assertTrue(blankUrl.getMessage().contains("url"));
        assertTrue(format.getMessage().contains("format"));
        assertTrue(provider.getMessage().contains("provider"));
        assertTrue(provider.getMessage().contains("不支持"));
    }
}
