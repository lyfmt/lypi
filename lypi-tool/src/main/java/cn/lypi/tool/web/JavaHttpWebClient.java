package cn.lypi.tool.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * 使用 JDK HttpClient 执行 provider HTTP 请求。
 */
public final class JavaHttpWebClient {
    private final HttpTransport transport;
    private final ObjectMapper objectMapper;
    private final Duration timeout;

    public JavaHttpWebClient() {
        this(
            new JdkHttpTransport(HttpClient.newHttpClient()),
            new ObjectMapper(),
            Duration.ofSeconds(20)
        );
    }

    public JavaHttpWebClient(HttpTransport transport, ObjectMapper objectMapper, Duration timeout) {
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.timeout = timeout == null ? Duration.ofSeconds(20) : timeout;
    }

    /**
     * 发送 JSON POST 请求。
     */
    public JsonNode postJson(URI uri, Map<String, String> headers, JsonNode body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body.toString()));
        addHeaders(builder, headers);
        return parseJson(sendText(builder.build()));
    }

    /**
     * 发送 JSON POST 请求并返回原始文本。
     */
    public String postText(URI uri, Map<String, String> headers, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body));
        addHeaders(builder, headers);
        return sendText(builder.build());
    }

    /**
     * 发送 GET 请求。
     */
    public JsonNode get(URI uri, Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
            .timeout(timeout)
            .GET();
        addHeaders(builder, headers);
        return parseJson(sendText(builder.build()));
    }

    private String sendText(HttpRequest request) {
        try {
            HttpResponse<String> response = transport.send(request);
            int status = response.statusCode();
            if (status == 401 || status == 403) {
                throw new WebProviderException("provider 鉴权失败。");
            }
            if (status == 429) {
                throw new WebProviderException("provider rate limit。");
            }
            if (status < 200 || status >= 300) {
                throw new WebProviderException("provider HTTP " + status + "。");
            }
            return response.body() == null ? "" : response.body();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new WebProviderException("provider 请求被中断。", exception);
        } catch (IOException exception) {
            throw new WebProviderException("provider 请求失败: " + exception.getMessage(), exception);
        }
    }

    private JsonNode parseJson(String body) {
        try {
            return objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (IOException exception) {
            throw new WebProviderException("provider 响应解析失败: " + exception.getMessage(), exception);
        }
    }

    private static void addHeaders(HttpRequest.Builder builder, Map<String, String> headers) {
        if (headers == null) {
            return;
        }
        headers.forEach((name, value) -> {
            if (name != null && value != null) {
                builder.header(name, value);
            }
        });
    }

    /**
     * HTTP transport seam for provider tests.
     */
    public interface HttpTransport {
        HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException;
    }

    private record JdkHttpTransport(HttpClient client) implements HttpTransport {
        @Override
        public HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }
}
