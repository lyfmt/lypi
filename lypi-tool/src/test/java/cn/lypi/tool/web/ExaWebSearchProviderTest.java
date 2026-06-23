package cn.lypi.tool.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cn.lypi.contracts.web.WebSearchResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class ExaWebSearchProviderTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void mapsMcpSearchRequestAndPlainJsonResponse() throws Exception {
        RecordingHttpTransport transport = new RecordingHttpTransport();
        transport.responseBody = """
            {
              "jsonrpc": "2.0",
              "id": "lypi-1",
              "result": {
                "content": [
                  {
                    "type": "text",
                    "text": "{\\"requestId\\":\\"req-1\\",\\"results\\":[{\\"title\\":\\"Exa Result\\",\\"url\\":\\"https://example.com/exa\\",\\"summary\\":\\"Short snippet\\",\\"text\\":\\"Full page text\\",\\"publishedDate\\":\\"2026-06-22T00:00:00Z\\",\\"score\\":0.72,\\"favicon\\":\\"https://example.com/favicon.ico\\"}]}"
                  }
                ]
              }
            }
            """;
        ExaWebSearchProvider provider = provider(transport);

        WebSearchResponse response = provider.search(new WebSearchRequest(
            "exa mcp",
            4,
            List.of("example.com"),
            List.of("blocked.example"),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            true
        ));

        JsonNode request = objectMapper.readTree(transport.requestBody);
        JsonNode arguments = request.path("params").path("arguments");
        assertEquals("POST", transport.request.method());
        assertEquals("https://mcp.exa.ai/mcp", transport.request.uri().toString());
        assertEquals(Optional.of("application/json, text/event-stream"), transport.request.headers().firstValue("Accept"));
        assertEquals("2.0", request.get("jsonrpc").asText());
        assertEquals("tools/call", request.get("method").asText());
        assertEquals("web_search_exa", request.path("params").path("name").asText());
        assertEquals("exa mcp", arguments.get("query").asText());
        assertEquals(4, arguments.get("numResults").asInt());
        assertEquals("fallback", arguments.get("livecrawl").asText());
        assertEquals("auto", arguments.get("type").asText());
        assertTrue(arguments.get("contextMaxCharacters").asInt() > 0);
        assertEquals("example.com", arguments.get("includeDomains").get(0).asText());
        assertEquals("blocked.example", arguments.get("excludeDomains").get(0).asText());
        assertEquals("exa", response.provider());
        assertEquals("exa mcp", response.query());
        assertEquals(Optional.empty(), response.answer());
        assertEquals(1, response.results().size());
        assertEquals("Exa Result", response.results().getFirst().title());
        assertEquals("https://example.com/exa", response.results().getFirst().url());
        assertEquals(Optional.of("Short snippet"), response.results().getFirst().snippet());
        assertEquals(Optional.of("Full page text"), response.results().getFirst().content());
        assertEquals(Optional.of(0.72d), response.results().getFirst().score());
        assertEquals(Optional.of("https://example.com/favicon.ico"), response.results().getFirst().favicon());
        assertEquals(Optional.of("req-1"), response.usage().orElseThrow().requestId());
    }

    @Test
    void parsesSseDataPayload() {
        RecordingHttpTransport transport = new RecordingHttpTransport();
        transport.responseBody = """
            event: message
            data: {"jsonrpc":"2.0","id":"lypi-1","result":{"structuredContent":{"results":[{"title":"SSE Result","url":"https://example.com/sse","snippet":"SSE snippet"}]}}}

            data: [DONE]
            """;
        ExaWebSearchProvider provider = provider(transport);

        WebSearchResponse response = provider.search(new WebSearchRequest(
            "sse",
            2,
            List.of(),
            List.of(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            false
        ));

        assertEquals("exa", response.provider());
        assertEquals(1, response.results().size());
        assertEquals("SSE Result", response.results().getFirst().title());
        assertEquals(Optional.of("SSE snippet"), response.results().getFirst().snippet());
    }

    @Test
    void mapsMcpPlainTextContentResponse() {
        RecordingHttpTransport transport = new RecordingHttpTransport();
        transport.responseBody = """
            event: message
            data: {"jsonrpc":"2.0","id":"lypi-1","result":{"content":[{"type":"text","text":"Title: Codex | AI Coding Partner from OpenAI | OpenAI\\nURL: https://openai.com/codex/\\nPublished Date: 2026-06-02T00:00:00.000Z\\nAuthor: OpenAI\\nText: OpenAI Codex is an AI coding partner."}]}}

            """;
        ExaWebSearchProvider provider = provider(transport);

        WebSearchResponse response = provider.search(new WebSearchRequest(
            "OpenAI Codex",
            1,
            List.of(),
            List.of(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            false
        ));

        assertEquals(1, response.results().size());
        assertEquals("Codex | AI Coding Partner from OpenAI | OpenAI", response.results().getFirst().title());
        assertEquals("https://openai.com/codex/", response.results().getFirst().url());
        assertEquals(Optional.of("OpenAI Codex is an AI coding partner."), response.results().getFirst().content());
        assertTrue(response.results().getFirst().publishedAt().isPresent());
    }

    @Test
    void mapsMultiplePlainTextResultsSeparatedByDivider() {
        RecordingHttpTransport transport = new RecordingHttpTransport();
        transport.responseBody = """
            event: message
            data: {"jsonrpc":"2.0","id":"lypi-1","result":{"content":[{"type":"text","text":"Title: Codex | AI Coding Partner from OpenAI | OpenAI\\nURL: https://openai.com/codex/\\nPublished: N/A\\nAuthor: N/A\\nHighlights:\\nCodex product page\\n...\\nA coding agent that helps you build and ship with AI.\\n\\n---\\n\\nTitle: Codex | OpenAI Developers\\nURL: https://developers.openai.com/codex\\nPublished: N/A\\nAuthor: N/A\\nHighlights:\\n# Codex\\n\\nCodex is OpenAI's coding agent for software development."}]}}

            """;
        ExaWebSearchProvider provider = provider(transport);

        WebSearchResponse response = provider.search(new WebSearchRequest(
            "OpenAI Codex",
            2,
            List.of(),
            List.of(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            false
        ));

        assertEquals(2, response.results().size());
        assertEquals("Codex | AI Coding Partner from OpenAI | OpenAI", response.results().get(0).title());
        assertEquals("https://openai.com/codex/", response.results().get(0).url());
        assertEquals(Optional.of("Codex product page\nA coding agent that helps you build and ship with AI."), response.results().get(0).content());
        assertTrue(response.results().get(0).publishedAt().isEmpty());
        assertEquals("Codex | OpenAI Developers", response.results().get(1).title());
        assertEquals("https://developers.openai.com/codex", response.results().get(1).url());
        assertEquals(Optional.of("# Codex\nCodex is OpenAI's coding agent for software development."), response.results().get(1).content());
    }

    @Test
    void keepsReservedLabelsInsidePlainTextBody() {
        RecordingHttpTransport transport = new RecordingHttpTransport();
        transport.responseBody = """
            event: message
            data: {"jsonrpc":"2.0","id":"lypi-1","result":{"content":[{"type":"text","text":"Title: Original Result\\nURL: https://example.com/original\\nPublished: N/A\\nAuthor: N/A\\nHighlights:\\nTitle: this line is part of the page body\\nURL: https://example.com/body-link"}]}}

            """;
        ExaWebSearchProvider provider = provider(transport);

        WebSearchResponse response = provider.search(new WebSearchRequest(
            "reserved labels",
            1,
            List.of(),
            List.of(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            false
        ));

        assertEquals(1, response.results().size());
        assertEquals("Original Result", response.results().getFirst().title());
        assertEquals("https://example.com/original", response.results().getFirst().url());
        assertEquals(
            Optional.of("Title: this line is part of the page body\nURL: https://example.com/body-link"),
            response.results().getFirst().content()
        );
    }

    @Test
    void usesConfiguredEndpoint() {
        RecordingHttpTransport transport = new RecordingHttpTransport();
        ExaWebSearchProvider provider = new ExaWebSearchProvider(
            new JavaHttpWebClient(transport, objectMapper, Duration.ofSeconds(5)),
            objectMapper,
            "https://exa.internal/mcp"
        );

        provider.search(new WebSearchRequest(
            "configured",
            2,
            List.of(),
            List.of(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            false
        ));

        assertEquals("https://exa.internal/mcp", transport.request.uri().toString());
    }

    @Test
    void httpErrorsBecomeProviderException() {
        RecordingHttpTransport transport = new RecordingHttpTransport();
        transport.responseStatus = 429;
        ExaWebSearchProvider provider = provider(transport);

        WebProviderException exception = assertThrows(
            WebProviderException.class,
            () -> provider.search(new WebSearchRequest(
                "rate limited",
                2,
                List.of(),
                List.of(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                false
            ))
        );

        assertTrue(exception.getMessage().contains("rate limit"));
        assertFalse(exception.getMessage().contains("web_search_exa"));
    }

    private ExaWebSearchProvider provider(RecordingHttpTransport transport) {
        return new ExaWebSearchProvider(
            new JavaHttpWebClient(transport, objectMapper, Duration.ofSeconds(5)),
            objectMapper
        );
    }
}
