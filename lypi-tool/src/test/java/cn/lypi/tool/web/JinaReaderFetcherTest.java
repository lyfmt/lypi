package cn.lypi.tool.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

final class JinaReaderFetcherTest {
    @Test
    void fetchesReaderMarkdownWithPrefixedUrlRule() {
        RecordingHttpTransport transport = new RecordingHttpTransport();
        transport.responseBody = "Title: Example\n\nMarkdown Content:\nReader body";
        JinaReaderFetcher fetcher = new JinaReaderFetcher(
            new JavaHttpWebClient(transport, new com.fasterxml.jackson.databind.ObjectMapper(), Duration.ofSeconds(5))
        );

        WebPageFetchResult result = fetcher.fetch("https://example.com/a");

        assertEquals("GET", transport.request.method());
        assertEquals("https://r.jina.ai/http://https://example.com/a", transport.request.uri().toString());
        assertEquals("text/markdown; charset=utf-8", result.contentType());
        assertEquals("https://example.com/a", result.finalUrl());
        assertTrue(result.body().contains("Reader body"));
    }

    @Test
    void rejectsUnsafeOriginalUrlBeforeReaderRequest() {
        RecordingHttpTransport transport = new RecordingHttpTransport();
        JinaReaderFetcher fetcher = new JinaReaderFetcher(
            new JavaHttpWebClient(transport, new com.fasterxml.jackson.databind.ObjectMapper(), Duration.ofSeconds(5))
        );

        WebProviderException exception = assertThrows(
            WebProviderException.class,
            () -> fetcher.fetch("http://127.0.0.1/admin")
        );

        assertTrue(exception.getMessage().contains("local"));
        assertEquals(null, transport.request);
    }

    @Test
    void httpErrorsBecomeProviderException() {
        RecordingHttpTransport transport = new RecordingHttpTransport();
        transport.responseStatus = 503;
        JinaReaderFetcher fetcher = new JinaReaderFetcher(
            new JavaHttpWebClient(transport, new com.fasterxml.jackson.databind.ObjectMapper(), Duration.ofSeconds(5))
        );

        WebProviderException exception = assertThrows(
            WebProviderException.class,
            () -> fetcher.fetch("https://example.com/a")
        );

        assertTrue(exception.getMessage().contains("HTTP 503"));
    }
}
