package cn.lypi.tool.mcp.stdio;

import cn.lypi.contracts.mcp.McpServerConfig;
import cn.lypi.contracts.mcp.McpTransport;
import cn.lypi.tool.mcp.McpClientException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 管理 STDIO MCP 外部端点子进程。
 */
final class StdioMcpProcess implements AutoCloseable {
    private final Process process;

    private StdioMcpProcess(Process process) {
        this.process = process;
    }

    static StdioMcpProcess start(McpServerConfig config, Path cwd) {
        Objects.requireNonNull(config, "config must not be null");
        if (config.transport() != McpTransport.STDIO || config.stdio() == null) {
            throw new McpClientException("MCP server is not configured for STDIO: " + config.name());
        }
        if (config.stdio().command() == null || config.stdio().command().isEmpty() || config.stdio().command().getFirst().isBlank()) {
            throw new McpClientException("MCP STDIO command is empty: " + config.name());
        }
        try {
            ProcessBuilder builder = new ProcessBuilder(config.stdio().command());
            if (cwd != null) {
                builder.directory(cwd.toFile());
            }
            builder.environment().putAll(config.stdio().env());
            return new StdioMcpProcess(builder.start());
        } catch (Exception exception) {
            throw new McpClientException("failed to start MCP STDIO server: " + config.name(), exception);
        }
    }

    InputStream inputStream() {
        return process.getInputStream();
    }

    OutputStream outputStream() {
        return process.getOutputStream();
    }

    InputStream errorStream() {
        return process.getErrorStream();
    }

    @Override
    public void close() {
        process.destroy();
        try {
            if (!process.waitFor(Duration.ofSeconds(2).toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}
