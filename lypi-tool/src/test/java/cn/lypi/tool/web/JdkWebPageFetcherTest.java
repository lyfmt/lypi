package cn.lypi.tool.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class JdkWebPageFetcherTest {
    @Test
    void fetchesTextContentWithUserAgent() {
        RecordingPageTransport transport = new RecordingPageTransport();
        transport.contentType = "text/html; charset=utf-8";
        transport.body = "<h1>Hello</h1>";
        JdkWebPageFetcher fetcher = new JdkWebPageFetcher(transport, Duration.ofSeconds(5), 10_000);

        WebPageFetchResult result = fetcher.fetch("https://example.com/doc");

        assertEquals("https://example.com/doc", result.finalUrl());
        assertEquals("text/html; charset=utf-8", result.contentType());
        assertEquals("<h1>Hello</h1>", result.body());
        assertEquals(Optional.of("ly-pi-web-fetch/1.0"), transport.request.headers().firstValue("User-Agent"));
        assertTrue(transport.request.headers().firstValue("Authorization").isEmpty());
    }

    @Test
    void rejectsHttpErrors() {
        RecordingPageTransport transport = new RecordingPageTransport();
        transport.status = 404;
        JdkWebPageFetcher fetcher = new JdkWebPageFetcher(transport, Duration.ofSeconds(5), 10_000);

        WebProviderException exception = assertThrows(
            WebProviderException.class,
            () -> fetcher.fetch("https://example.com/missing")
        );

        assertTrue(exception.getMessage().contains("HTTP 404"));
    }

    @Test
    void rejectsUnsupportedContentType() {
        RecordingPageTransport transport = new RecordingPageTransport();
        transport.contentType = "application/octet-stream";
        JdkWebPageFetcher fetcher = new JdkWebPageFetcher(transport, Duration.ofSeconds(5), 10_000);

        WebProviderException exception = assertThrows(
            WebProviderException.class,
            () -> fetcher.fetch("https://example.com/file")
        );

        assertTrue(exception.getMessage().contains("content-type"));
    }

    @Test
    void rejectsUnsafeFinalRedirectUrl() {
        RecordingPageTransport transport = new RecordingPageTransport();
        transport.finalUri = URI.create("http://127.0.0.1/admin");
        JdkWebPageFetcher fetcher = new JdkWebPageFetcher(transport, Duration.ofSeconds(5), 10_000);

        WebProviderException exception = assertThrows(
            WebProviderException.class,
            () -> fetcher.fetch("https://example.com/redirect")
        );

        assertTrue(exception.getMessage().contains("local"));
    }

    @Test
    void truncatesFetchedBodyAtConfiguredLimit() {
        RecordingPageTransport transport = new RecordingPageTransport();
        transport.body = "1234567890";
        JdkWebPageFetcher fetcher = new JdkWebPageFetcher(transport, Duration.ofSeconds(5), 4);

        WebPageFetchResult result = fetcher.fetch("https://example.com/doc");

        assertEquals("1234", result.body());
    }

    @Test
    void followsSafeSameHostRedirectManually() {
        RecordingPageTransport transport = new RecordingPageTransport();
        transport.responses.add(new JdkWebPageFetcher.PageResponse(
            URI.create("https://example.com/start"),
            302,
            headers("location", "/final"),
            ""
        ));
        transport.responses.add(new JdkWebPageFetcher.PageResponse(
            URI.create("https://example.com/final"),
            200,
            headers("content-type", "text/plain"),
            "done"
        ));
        JdkWebPageFetcher fetcher = new JdkWebPageFetcher(transport, Duration.ofSeconds(5), 10_000);

        WebPageFetchResult result = fetcher.fetch("https://example.com/start");

        assertEquals("https://example.com/final", result.finalUrl());
        assertEquals("done", result.body());
        assertEquals(2, transport.requests.size());
        assertEquals(URI.create("https://example.com/start"), transport.requests.get(0).uri());
        assertEquals(URI.create("https://example.com/final"), transport.requests.get(1).uri());
    }

    @Test
    void rejectsRedirectToUnsafeUrlBeforeIssuingSecondRequest() {
        RecordingPageTransport transport = new RecordingPageTransport();
        transport.responses.add(new JdkWebPageFetcher.PageResponse(
            URI.create("https://example.com/start"),
            302,
            headers("location", "http://127.0.0.1/admin"),
            ""
        ));
        JdkWebPageFetcher fetcher = new JdkWebPageFetcher(transport, Duration.ofSeconds(5), 10_000);

        WebProviderException exception = assertThrows(
            WebProviderException.class,
            () -> fetcher.fetch("https://example.com/start")
        );

        assertTrue(exception.getMessage().contains("local"));
        assertEquals(1, transport.requests.size());
    }

    @Test
    void rejectsRedirectToDifferentPublicHost() {
        RecordingPageTransport transport = new RecordingPageTransport();
        transport.responses.add(new JdkWebPageFetcher.PageResponse(
            URI.create("https://example.com/start"),
            302,
            headers("location", "https://other.example/final"),
            ""
        ));
        JdkWebPageFetcher fetcher = new JdkWebPageFetcher(transport, Duration.ofSeconds(5), 10_000);

        WebProviderException exception = assertThrows(
            WebProviderException.class,
            () -> fetcher.fetch("https://example.com/start")
        );

        assertTrue(exception.getMessage().contains("redirect host"));
        assertEquals(1, transport.requests.size());
    }

    @Test
    void readsOnlyConfiguredBytesFromBodyStream() throws Exception {
        String body = JdkWebPageFetcher.readLimited(
            new java.io.ByteArrayInputStream("1234567890".getBytes(StandardCharsets.UTF_8)),
            4,
            StandardCharsets.UTF_8
        );

        assertEquals("1234", body);
    }

    private static final class RecordingPageTransport implements JdkWebPageFetcher.PageTransport {
        HttpRequest request;
        List<HttpRequest> requests = new java.util.ArrayList<>();
        ArrayDeque<JdkWebPageFetcher.PageResponse> responses = new ArrayDeque<>();
        int status = 200;
        URI finalUri;
        String contentType = "text/plain";
        String body = "ok";

        @Override
        public JdkWebPageFetcher.PageResponse send(HttpRequest request, int maxBodyChars) throws IOException, InterruptedException {
            this.request = request;
            this.requests.add(request);
            if (!responses.isEmpty()) {
                return responses.removeFirst();
            }
            URI responseUri = finalUri == null ? request.uri() : finalUri;
            return new JdkWebPageFetcher.PageResponse(
                responseUri,
                status,
                headers(contentType == null ? null : "content-type", contentType),
                body.length() > maxBodyChars ? body.substring(0, maxBodyChars) : body
            );
        }
    }

    private static HttpHeaders headers(String name, String value) {
        if (name == null || value == null) {
            return HttpHeaders.of(Map.of(), (header, headerValue) -> true);
        }
        return HttpHeaders.of(Map.of(name, List.of(value)), (header, headerValue) -> true);
    }
}
