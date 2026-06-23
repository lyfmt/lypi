package cn.lypi.tool.web;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

/**
 * 通过 Jina Reader 抽取网页 Markdown。
 */
public final class JinaReaderFetcher implements WebPageFetcher {
    public static final String DEFAULT_ENDPOINT = "https://r.jina.ai/http://";

    private final JavaHttpWebClient client;
    private final URI endpoint;

    public JinaReaderFetcher() {
        this(new JavaHttpWebClient(), DEFAULT_ENDPOINT);
    }

    public JinaReaderFetcher(Duration timeout) {
        this(timeout, DEFAULT_ENDPOINT);
    }

    public JinaReaderFetcher(Duration timeout, String endpoint) {
        this(new JavaHttpWebClient(
            new JavaHttpWebClient.HttpTransport() {
                private final java.net.http.HttpClient delegate = java.net.http.HttpClient.newHttpClient();

                @Override
                public java.net.http.HttpResponse<String> send(java.net.http.HttpRequest request)
                    throws java.io.IOException, InterruptedException {
                    return delegate.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
                }
            },
            new com.fasterxml.jackson.databind.ObjectMapper(),
            timeout == null ? Duration.ofSeconds(20) : timeout
        ), endpoint);
    }

    public JinaReaderFetcher(JavaHttpWebClient client) {
        this(client, DEFAULT_ENDPOINT);
    }

    public JinaReaderFetcher(JavaHttpWebClient client, String endpoint) {
        this.client = client;
        this.endpoint = endpoint(endpoint, DEFAULT_ENDPOINT);
    }

    @Override
    public WebPageFetchResult fetch(String url) {
        WebUrlPolicy.CheckedUrl checked;
        try {
            checked = WebUrlPolicy.check(url);
        } catch (IllegalArgumentException exception) {
            throw new WebProviderException(exception.getMessage(), exception);
        }
        String body = client.getText(readerUri(checked.uri().toString()), Map.of(
            "Accept", "text/markdown, text/plain;q=0.9"
        ));
        return new WebPageFetchResult(checked.uri().toString(), "text/markdown; charset=utf-8", body, "jina");
    }

    private URI readerUri(String targetUrl) {
        return URI.create(endpoint.toString() + targetUrl);
    }

    private static URI endpoint(String endpoint, String defaultEndpoint) {
        String value = endpoint == null || endpoint.isBlank() ? defaultEndpoint : endpoint.trim();
        return URI.create(value);
    }
}
