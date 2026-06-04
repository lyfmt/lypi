package cn.lypi.ai.transport;

import cn.lypi.ai.provider.ListProviderEventStream;
import cn.lypi.ai.provider.ProviderEventStream;
import cn.lypi.ai.provider.ProviderRawEvent;
import cn.lypi.ai.provider.ProviderRequest;
import cn.lypi.ai.provider.ProviderTransport;
import cn.lypi.contracts.common.AbortSignal;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class HttpSseProviderTransport implements ProviderTransport {
    private final HttpClient httpClient;

    public HttpSseProviderTransport() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build());
    }

    public HttpSseProviderTransport(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public ProviderEventStream stream(ProviderRequest request, AbortSignal signal) {
        if (signal.aborted()) {
            return new ListProviderEventStream(List.of());
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri())
            .POST(HttpRequest.BodyPublishers.ofString(request.body()))
            .header("Content-Type", "application/json");
        request.timeout().ifPresent(builder::timeout);
        for (Map.Entry<String, String> header : request.headers().entrySet()) {
            builder.header(header.getKey(), header.getValue());
        }
        try {
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Provider HTTP " + response.statusCode() + ".");
            }
            return new ListProviderEventStream(parseSse(response.body()));
        } catch (IOException exception) {
            throw new IllegalStateException("Provider HTTP request failed.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Provider HTTP request interrupted.", exception);
        }
    }

    private List<ProviderRawEvent> parseSse(String body) {
        List<ProviderRawEvent> events = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : body.split("\\R", -1)) {
            if (line.isBlank()) {
                flush(events, current);
            } else if (line.startsWith("data:")) {
                if (!current.isEmpty()) {
                    current.append('\n');
                }
                current.append(line.substring("data:".length()).trim());
            }
        }
        flush(events, current);
        return events;
    }

    private void flush(List<ProviderRawEvent> events, StringBuilder current) {
        if (!current.isEmpty()) {
            events.add(new ProviderRawEvent(current.toString()));
            current.setLength(0);
        }
    }
}
