package cn.lypi.tool.mcp.jsonrpc;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.concurrent.CompletableFuture;

/**
 * 发送 JSON-RPC 请求和通知。
 */
public interface JsonRpcEndpoint extends AutoCloseable {
    /**
     * 启动响应读取循环。
     */
    void start();

    /**
     * 发送请求并返回匹配响应。
     */
    CompletableFuture<JsonNode> request(String method, Object params);

    /**
     * 发送不需要响应的通知。
     */
    void notify(String method, Object params);

    @Override
    void close();
}
