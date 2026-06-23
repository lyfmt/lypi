package cn.lypi.tool.web;

import java.util.Locale;
import java.util.Objects;
import org.jsoup.Jsoup;

/**
 * 本地抓取失败或内容过短时回退到 reader fetcher。
 */
public final class FallbackWebPageFetcher implements WebPageFetcher {
    private final WebPageFetcher localFetcher;
    private final WebPageFetcher fallbackFetcher;
    private final int minBodyChars;

    public FallbackWebPageFetcher(WebPageFetcher localFetcher, WebPageFetcher fallbackFetcher, int minBodyChars) {
        this.localFetcher = Objects.requireNonNull(localFetcher, "localFetcher must not be null");
        this.fallbackFetcher = Objects.requireNonNull(fallbackFetcher, "fallbackFetcher must not be null");
        this.minBodyChars = Math.max(0, minBodyChars);
    }

    @Override
    public WebPageFetchResult fetch(String url) {
        try {
            WebPageFetchResult result = localFetcher.fetch(url);
            if (readableLength(result) < minBodyChars) {
                return fallbackFetcher.fetch(url);
            }
            return result;
        } catch (WebProviderException exception) {
            if (recoverable(exception)) {
                return fallbackFetcher.fetch(url);
            }
            throw exception;
        }
    }

    private int readableLength(WebPageFetchResult result) {
        if (result == null) {
            return 0;
        }
        String body = result.body() == null ? "" : result.body();
        String contentType = result.contentType() == null ? "" : result.contentType().toLowerCase(Locale.ROOT);
        if (contentType.contains("html") || body.toLowerCase(Locale.ROOT).contains("<html")) {
            return Jsoup.parse(body, result.finalUrl()).text().trim().length();
        }
        return body.trim().length();
    }

    private boolean recoverable(WebProviderException exception) {
        String message = exception.getMessage() == null ? "" : exception.getMessage().toLowerCase(Locale.ROOT);
        if (message.contains("url host")
            || message.contains("credential")
            || message.contains("redirect host")
            || message.contains("url scheme")
            || message.contains("url 格式")) {
            return false;
        }
        return message.contains("http 403")
            || message.contains("http 406")
            || message.contains("http 429")
            || message.contains("http 5")
            || message.contains("content-type");
    }
}
