package cn.lypi.tool.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class FallbackWebPageFetcherTest {
    @Test
    void fallsBackWhenLocalFetchHasRecoverableHttpError() {
        AtomicInteger fallbackCalls = new AtomicInteger();
        WebPageFetcher local = url -> {
            throw new WebProviderException("本地网页抓取 HTTP 403。");
        };
        WebPageFetcher fallback = url -> {
            fallbackCalls.incrementAndGet();
            return new WebPageFetchResult(url, "text/markdown", "reader content");
        };
        FallbackWebPageFetcher fetcher = new FallbackWebPageFetcher(local, fallback, 200);

        WebPageFetchResult result = fetcher.fetch("https://example.com/doc");

        assertEquals("reader content", result.body());
        assertEquals(1, fallbackCalls.get());
    }

    @Test
    void fallsBackWhenLocalBodyIsTooShort() {
        AtomicInteger fallbackCalls = new AtomicInteger();
        WebPageFetcher local = url -> new WebPageFetchResult(url, "text/html", "<html><body>short</body></html>");
        WebPageFetcher fallback = url -> {
            fallbackCalls.incrementAndGet();
            return new WebPageFetchResult(url, "text/markdown", "long enough reader content");
        };
        FallbackWebPageFetcher fetcher = new FallbackWebPageFetcher(local, fallback, 10);

        WebPageFetchResult result = fetcher.fetch("https://example.com/doc");

        assertEquals("long enough reader content", result.body());
        assertEquals(1, fallbackCalls.get());
    }

    @Test
    void doesNotFallbackForUnsafeUrlFailures() {
        AtomicInteger fallbackCalls = new AtomicInteger();
        WebPageFetcher local = url -> {
            throw new WebProviderException("url host 不能是 local 地址。");
        };
        WebPageFetcher fallback = url -> {
            fallbackCalls.incrementAndGet();
            return new WebPageFetchResult(url, "text/markdown", "reader content");
        };
        FallbackWebPageFetcher fetcher = new FallbackWebPageFetcher(local, fallback, 200);

        assertThrows(WebProviderException.class, () -> fetcher.fetch("http://127.0.0.1/admin"));
        assertEquals(0, fallbackCalls.get());
    }
}
