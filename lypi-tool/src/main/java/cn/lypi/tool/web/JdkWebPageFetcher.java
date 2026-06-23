package cn.lypi.tool.web;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * 使用 JDK HttpClient 抓取公开网页。
 */
public final class JdkWebPageFetcher implements WebPageFetcher {
    private static final String USER_AGENT = "ly-pi-web-fetch/1.0";
    private static final int DEFAULT_MAX_BODY_CHARS = 200_000;
    private static final int MAX_REDIRECTS = 5;

    private final PageTransport transport;
    private final Duration timeout;
    private final int maxBodyChars;

    public JdkWebPageFetcher(Duration timeout) {
        this(new JdkPageTransport(), timeout, DEFAULT_MAX_BODY_CHARS);
    }

    JdkWebPageFetcher(PageTransport transport, Duration timeout, int maxBodyChars) {
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
        this.timeout = timeout == null ? Duration.ofSeconds(20) : timeout;
        this.maxBodyChars = Math.max(1, maxBodyChars);
    }

    @Override
    public WebPageFetchResult fetch(String url) {
        WebUrlPolicy.CheckedUrl original = checkUrl(url);
        URI currentUri = original.uri();
        try {
            for (int redirectCount = 0; redirectCount <= MAX_REDIRECTS; redirectCount++) {
                PageResponse response = transport.send(request(currentUri), maxBodyChars);
                int status = response.statusCode();
                if (isRedirect(status)) {
                    currentUri = redirectUri(currentUri, response, original.host());
                    continue;
                }
                if (status < 200 || status >= 300) {
                    throw new WebProviderException("本地网页抓取 HTTP " + status + "。");
                }
                WebUrlPolicy.CheckedUrl finalUrl = checkUrl(response.uri().toString());
                requireSameHost(original.host(), finalUrl.host());
                String contentType = response.headers().firstValue("content-type").orElse("text/plain");
                if (!supportedContentType(contentType)) {
                    throw new WebProviderException("不支持的 content-type: " + contentType + "。");
                }
                return new WebPageFetchResult(
                    response.uri().toString(),
                    contentType,
                    response.body() == null ? "" : response.body()
                );
            }
            throw new WebProviderException("本地网页抓取 redirect 过多。");
        } catch (IOException exception) {
            throw new WebProviderException("本地网页抓取失败: " + exception.getMessage(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new WebProviderException("本地网页抓取被中断。", exception);
        }
    }

    private HttpRequest request(URI uri) {
        return HttpRequest.newBuilder(uri)
            .timeout(timeout)
            .header("Accept", "text/html, text/plain, application/json, application/xml, text/xml;q=0.9, */*;q=0.1")
            .header("User-Agent", USER_AGENT)
            .GET()
            .build();
    }

    private WebUrlPolicy.CheckedUrl checkUrl(String url) {
        try {
            return WebUrlPolicy.check(url);
        } catch (IllegalArgumentException exception) {
            throw new WebProviderException(exception.getMessage(), exception);
        }
    }

    private URI redirectUri(URI currentUri, PageResponse response, String originalHost) {
        String location = response.headers().firstValue("location")
            .orElseThrow(() -> new WebProviderException("本地网页抓取 redirect 缺少 Location。"));
        URI redirected = currentUri.resolve(location);
        WebUrlPolicy.CheckedUrl checked = checkUrl(redirected.toString());
        requireSameHost(originalHost, checked.host());
        return checked.uri();
    }

    private void requireSameHost(String originalHost, String redirectedHost) {
        if (!originalHost.equalsIgnoreCase(redirectedHost)) {
            throw new WebProviderException("本地网页抓取 redirect host 必须保持一致。");
        }
    }

    private boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private boolean supportedContentType(String contentType) {
        String normalized = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        return normalized.startsWith("text/")
            || normalized.contains("application/json")
            || normalized.contains("application/xml")
            || normalized.contains("+json")
            || normalized.contains("+xml");
    }

    /**
     * HTTP transport seam for tests.
     */
    interface PageTransport {
        PageResponse send(HttpRequest request, int maxBodyChars) throws IOException, InterruptedException;
    }

    record PageResponse(
        URI uri,
        int statusCode,
        HttpHeaders headers,
        String body
    ) {
        PageResponse {
            headers = headers == null ? HttpHeaders.of(java.util.Map.of(), (name, value) -> true) : headers;
            body = body == null ? "" : body;
        }
    }

    private static final class JdkPageTransport implements PageTransport {
        private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

        @Override
        public PageResponse send(HttpRequest request, int maxBodyChars) throws IOException, InterruptedException {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            return new PageResponse(
                response.uri(),
                response.statusCode(),
                response.headers(),
                readLimited(response.body(), maxBodyChars, charset(response.headers()))
            );
        }
    }

    static String readLimited(InputStream input, int maxChars, Charset charset) throws IOException {
        if (input == null) {
            return "";
        }
        int limit = Math.max(1, maxChars);
        byte[] bytes = input.readNBytes(limit);
        return new String(bytes, charset == null ? StandardCharsets.UTF_8 : charset);
    }

    private static Charset charset(HttpHeaders headers) {
        Optional<String> contentType = headers == null ? Optional.empty() : headers.firstValue("content-type");
        return contentType.flatMap(JdkWebPageFetcher::charsetFromContentType).orElse(StandardCharsets.UTF_8);
    }

    private static Optional<Charset> charsetFromContentType(String contentType) {
        for (String part : contentType.split(";")) {
            String trimmed = part.trim();
            if (trimmed.toLowerCase(Locale.ROOT).startsWith("charset=")) {
                try {
                    return Optional.of(Charset.forName(trimmed.substring("charset=".length()).trim()));
                } catch (RuntimeException exception) {
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }
}
