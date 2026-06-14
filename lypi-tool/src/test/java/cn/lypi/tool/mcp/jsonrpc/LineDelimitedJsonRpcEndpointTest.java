package cn.lypi.tool.mcp.jsonrpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.tool.mcp.McpProtocolException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(5)
class LineDelimitedJsonRpcEndpointTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void requestCompletesWhenMatchingResponseArrives() throws Exception {
        TestPipes pipes = TestPipes.create();
        LineDelimitedJsonRpcEndpoint endpoint = endpoint(pipes.clientInput(), pipes.clientOutput());
        endpoint.start();

        CompletableFuture<JsonNode> result = endpoint.request("tools/list", Map.of());
        JsonNode request = pipes.readServerRequest();
        assertEquals("2.0", request.path("jsonrpc").asText());
        assertEquals("tools/list", request.path("method").asText());
        pipes.writeServerResponse("""
            {"jsonrpc":"2.0","id":%d,"result":{"tools":[]}}
            """.formatted(request.path("id").asLong()));

        assertTrue(result.get(1, TimeUnit.SECONDS).path("tools").isArray());
        pipes.closeServer();
        endpoint.close();
    }

    @Test
    void requestFailsWhenErrorResponseArrives() throws Exception {
        TestPipes pipes = TestPipes.create();
        LineDelimitedJsonRpcEndpoint endpoint = endpoint(pipes.clientInput(), pipes.clientOutput());
        endpoint.start();

        CompletableFuture<JsonNode> result = endpoint.request("initialize", Map.of());
        JsonNode request = pipes.readServerRequest();
        pipes.writeServerResponse("""
            {"jsonrpc":"2.0","id":%d,"error":{"code":-32601,"message":"Method not found"}}
            """.formatted(request.path("id").asLong()));

        ExecutionException exception = org.junit.jupiter.api.Assertions.assertThrows(
            ExecutionException.class,
            () -> result.get(1, TimeUnit.SECONDS)
        );
        assertInstanceOf(McpProtocolException.class, exception.getCause());
        assertTrue(exception.getMessage().contains("Method not found"));
        pipes.closeServer();
        endpoint.close();
    }

    @Test
    void notificationWritesMessageWithoutId() throws Exception {
        TestPipes pipes = TestPipes.create();
        LineDelimitedJsonRpcEndpoint endpoint = endpoint(pipes.clientInput(), pipes.clientOutput());

        endpoint.notify("notifications/initialized", Map.of());
        JsonNode notification = pipes.readServerRequest();

        assertEquals("2.0", notification.path("jsonrpc").asText());
        assertEquals("notifications/initialized", notification.path("method").asText());
        assertFalse(notification.has("id"));
        endpoint.close();
    }

    @Test
    void invalidStdoutFailsPendingRequests() throws Exception {
        TestPipes pipes = TestPipes.create();
        LineDelimitedJsonRpcEndpoint endpoint = endpoint(pipes.clientInput(), pipes.clientOutput());
        endpoint.start();

        CompletableFuture<JsonNode> result = endpoint.request("tools/list", Map.of());
        pipes.readServerRequest();
        pipes.writeServerResponse("not-json\n");

        ExecutionException exception = org.junit.jupiter.api.Assertions.assertThrows(
            ExecutionException.class,
            () -> result.get(1, TimeUnit.SECONDS)
        );
        assertTrue(exception.getMessage().contains("invalid JSON-RPC message"));
        pipes.closeServer();
        endpoint.close();
    }

    private LineDelimitedJsonRpcEndpoint endpoint(InputStream input, OutputStream output) {
        return new LineDelimitedJsonRpcEndpoint(input, output, JSON, Duration.ofSeconds(1), message -> {
        });
    }

    private static final class TestPipes {
        private final PipedInputStream clientInput;
        private final QueueLineOutputStream clientOutput;
        private final PipedOutputStream serverWriter;

        private TestPipes(
            PipedInputStream clientInput,
            QueueLineOutputStream clientOutput,
            PipedOutputStream serverWriter
        ) {
            this.clientInput = clientInput;
            this.clientOutput = clientOutput;
            this.serverWriter = serverWriter;
        }

        private static TestPipes create() throws IOException {
            PipedInputStream clientInput = new PipedInputStream(8_192);
            PipedOutputStream serverWriter = new PipedOutputStream(clientInput);
            QueueLineOutputStream clientOutput = new QueueLineOutputStream();
            return new TestPipes(clientInput, clientOutput, serverWriter);
        }

        private InputStream clientInput() {
            return clientInput;
        }

        private OutputStream clientOutput() {
            return clientOutput;
        }

        private JsonNode readServerRequest() throws Exception {
            String line = clientOutput.readLine();
            return JSON.readTree(line);
        }

        private void writeServerResponse(String response) throws IOException {
            serverWriter.write(response.getBytes(StandardCharsets.UTF_8));
            serverWriter.flush();
        }

        private void closeServer() throws IOException {
            serverWriter.close();
        }
    }

    private static final class QueueLineOutputStream extends OutputStream {
        private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        @Override
        public synchronized void write(int value) {
            if (value == '\n') {
                lines.add(buffer.toString(StandardCharsets.UTF_8));
                buffer.reset();
                return;
            }
            if (value != '\r') {
                buffer.write(value);
            }
        }

        private String readLine() throws InterruptedException {
            String line = lines.poll(1, TimeUnit.SECONDS);
            if (line == null) {
                throw new AssertionError("JSON-RPC request was not written");
            }
            return line;
        }
    }
}
