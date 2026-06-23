package cn.lypi.tool.web;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLSession;

final class RecordingHttpTransport implements JavaHttpWebClient.HttpTransport {
    HttpRequest request;
    String requestBody = "";
    int responseStatus = 200;
    String responseBody = "{}";

    @Override
    public HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        this.request = request;
        this.requestBody = readBody(request);
        return new RecordingResponse(request.uri(), responseStatus, responseBody);
    }

    private static String readBody(HttpRequest request) throws InterruptedException {
        Optional<HttpRequest.BodyPublisher> publisher = request.bodyPublisher();
        if (publisher.isEmpty()) {
            return "";
        }
        BodySubscriber subscriber = new BodySubscriber();
        publisher.orElseThrow().subscribe(subscriber);
        return subscriber.body();
    }

    private record RecordingResponse(URI uri, int statusCode, String body) implements HttpResponse<String> {
        @Override
        public HttpRequest request() {
            return null;
        }

        @Override
        public Optional<HttpResponse<String>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(Map.of(), (name, value) -> true);
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return uri;
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }

    private static final class BodySubscriber implements Flow.Subscriber<ByteBuffer> {
        private final CountDownLatch done = new CountDownLatch(1);
        private final List<ByteBuffer> buffers = new ArrayList<>();
        private Throwable error;

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(ByteBuffer item) {
            buffers.add(item.asReadOnlyBuffer());
        }

        @Override
        public void onError(Throwable throwable) {
            error = throwable;
            done.countDown();
        }

        @Override
        public void onComplete() {
            done.countDown();
        }

        String body() throws InterruptedException {
            if (!done.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("body publisher did not complete");
            }
            if (error != null) {
                throw new IllegalStateException(error);
            }
            int size = buffers.stream().mapToInt(ByteBuffer::remaining).sum();
            ByteBuffer joined = ByteBuffer.allocate(size);
            for (ByteBuffer buffer : buffers) {
                joined.put(buffer);
            }
            joined.flip();
            return StandardCharsets.UTF_8.decode(joined).toString();
        }
    }
}
