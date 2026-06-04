package cn.lypi.ai.transport;

import cn.lypi.ai.provider.ListProviderEventStream;
import cn.lypi.ai.provider.ProviderEventStream;
import cn.lypi.ai.provider.ProviderRawEvent;
import cn.lypi.ai.provider.ProviderRequest;
import cn.lypi.ai.provider.ProviderTransport;
import cn.lypi.contracts.common.AbortSignal;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

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
            HttpResponse<InputStream> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                closeQuietly(response.body());
                throw new IllegalStateException("Provider HTTP " + response.statusCode() + ".");
            }
            return new SseProviderEventStream(response.body(), signal);
        } catch (IOException exception) {
            throw new IllegalStateException("Provider HTTP request failed.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Provider HTTP request interrupted.", exception);
        }
    }

    private static void closeQuietly(InputStream body) {
        try {
            body.close();
        } catch (IOException ignored) {
            // ignore close failure for an already-failed HTTP response
        }
    }

    private static final class SseProviderEventStream implements ProviderEventStream {
        private final InputStream body;
        private final BufferedReader reader;
        private final AbortSignal signal;
        private boolean closed;
        private boolean iteratorCreated;

        private SseProviderEventStream(InputStream body, AbortSignal signal) {
            this.body = body;
            this.reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
            this.signal = signal;
        }

        @Override
        public Iterator<ProviderRawEvent> iterator() {
            if (iteratorCreated) {
                throw new IllegalStateException("Provider HTTP stream is single-use.");
            }
            iteratorCreated = true;
            return new SseIterator();
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                body.close();
            } catch (IOException exception) {
                throw new IllegalStateException("Provider HTTP stream failed.", exception);
            }
        }

        private final class SseIterator implements Iterator<ProviderRawEvent> {
            private final StringBuilder current = new StringBuilder();
            private ProviderRawEvent next;
            private boolean eof;

            @Override
            public boolean hasNext() {
                if (next != null) {
                    return true;
                }
                if (eof || closed || signal.aborted()) {
                    close();
                    return false;
                }
                next = readNext();
                return next != null;
            }

            @Override
            public ProviderRawEvent next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                ProviderRawEvent event = next;
                next = null;
                return event;
            }

            private ProviderRawEvent readNext() {
                try {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.isBlank()) {
                            ProviderRawEvent flushed = flush();
                            if (flushed != null) {
                                return flushed;
                            }
                        } else if (line.startsWith("data:")) {
                            if (!current.isEmpty()) {
                                current.append('\n');
                            }
                            current.append(line.substring("data:".length()).trim());
                        }
                        if (closed || signal.aborted()) {
                            close();
                            return null;
                        }
                    }
                    eof = true;
                    close();
                    return flush();
                } catch (IOException exception) {
                    close();
                    throw new IllegalStateException("Provider HTTP stream failed.", exception);
                }
            }

            private ProviderRawEvent flush() {
                if (current.isEmpty()) {
                    return null;
                }
                ProviderRawEvent event = new ProviderRawEvent(current.toString());
                current.setLength(0);
                return event;
            }
        }
    }
}
