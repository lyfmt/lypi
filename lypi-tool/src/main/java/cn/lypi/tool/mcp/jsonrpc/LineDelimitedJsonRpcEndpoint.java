package cn.lypi.tool.mcp.jsonrpc;

import cn.lypi.tool.mcp.McpClientException;
import cn.lypi.tool.mcp.McpProtocolException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 基于行分隔 JSON 消息的 JSON-RPC 端点。
 */
public final class LineDelimitedJsonRpcEndpoint implements JsonRpcEndpoint {
    private final BufferedReader reader;
    private final BufferedWriter writer;
    private final ObjectMapper jsonMapper;
    private final Duration timeout;
    private final Consumer<String> diagnostics;
    private final AtomicLong nextId = new AtomicLong(1);
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "mcp-jsonrpc-timeouts");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Object writeLock = new Object();

    public LineDelimitedJsonRpcEndpoint(
        InputStream input,
        OutputStream output,
        ObjectMapper jsonMapper,
        Duration timeout,
        Consumer<String> diagnostics
    ) {
        this.reader = new BufferedReader(new InputStreamReader(Objects.requireNonNull(input, "input must not be null"), StandardCharsets.UTF_8));
        this.writer = new BufferedWriter(new OutputStreamWriter(Objects.requireNonNull(output, "output must not be null"), StandardCharsets.UTF_8));
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper must not be null");
        this.timeout = timeout == null ? Duration.ofSeconds(60) : timeout;
        this.diagnostics = diagnostics == null ? message -> {
        } : diagnostics;
    }

    @Override
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        Thread readerThread = new Thread(this::readLoop, "mcp-jsonrpc-reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    @Override
    public CompletableFuture<JsonNode> request(String method, Object params) {
        return request(method, params, timeout);
    }

    @Override
    public CompletableFuture<JsonNode> request(String method, Object params, Duration requestTimeout) {
        ensureOpen();
        long id = nextId.getAndIncrement();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        Duration effectiveTimeout = requestTimeout == null ? timeout : requestTimeout;
        scheduler.schedule(() -> {
            CompletableFuture<JsonNode> removed = pending.remove(id);
            if (removed != null) {
                removed.completeExceptionally(new McpClientException("JSON-RPC request timed out: " + method));
            }
        }, effectiveTimeout.toMillis(), TimeUnit.MILLISECONDS);
        try {
            writeMessage(requestMessage(id, method, params));
        } catch (RuntimeException exception) {
            pending.remove(id);
            future.completeExceptionally(exception);
        }
        return future;
    }

    @Override
    public void notify(String method, Object params) {
        ensureOpen();
        writeMessage(notificationMessage(method, params));
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        failPending(new McpClientException("JSON-RPC endpoint closed"));
        scheduler.shutdownNow();
        try {
            writer.close();
        } catch (IOException exception) {
            diagnostics.accept("failed to close JSON-RPC writer: " + exception.getMessage());
        }
    }

    private void readLoop() {
        try {
            String line;
            while (!closed.get() && (line = reader.readLine()) != null) {
                handleLine(line);
            }
            if (!closed.get()) {
                failPending(new McpClientException("JSON-RPC endpoint closed by peer"));
            }
        } catch (IOException exception) {
            if (!closed.get()) {
                failPending(new McpClientException("failed to read JSON-RPC message", exception));
            }
        }
    }

    private void handleLine(String line) {
        JsonNode message;
        try {
            message = jsonMapper.readTree(line);
        } catch (JsonProcessingException exception) {
            failPending(new McpProtocolException("invalid JSON-RPC message: " + line, exception));
            return;
        }
        JsonNode idNode = message.path("id");
        if (!idNode.canConvertToLong()) {
            diagnostics.accept("ignoring JSON-RPC message without numeric id");
            return;
        }
        long id = idNode.asLong();
        CompletableFuture<JsonNode> future = pending.remove(id);
        if (future == null) {
            diagnostics.accept("ignoring JSON-RPC response for unknown id: " + id);
            return;
        }
        JsonNode error = message.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            future.completeExceptionally(new McpProtocolException(error.path("code").asInt(), error.path("message").asText("MCP protocol error")));
            return;
        }
        future.complete(message.path("result"));
    }

    private Map<String, Object> requestMessage(long id, String method, Object params) {
        Map<String, Object> message = notificationMessage(method, params);
        message.put("id", id);
        return message;
    }

    private Map<String, Object> notificationMessage(String method, Object params) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("jsonrpc", "2.0");
        message.put("method", method);
        message.put("params", params == null ? Map.of() : params);
        return message;
    }

    private void writeMessage(Map<String, Object> message) {
        synchronized (writeLock) {
            try {
                writer.write(jsonMapper.writeValueAsString(message));
                writer.newLine();
                writer.flush();
            } catch (IOException exception) {
                throw new McpClientException("failed to write JSON-RPC message", exception);
            }
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new McpClientException("JSON-RPC endpoint is closed");
        }
    }

    private void failPending(RuntimeException exception) {
        pending.values().forEach(future -> future.completeExceptionally(exception));
        pending.clear();
    }
}
