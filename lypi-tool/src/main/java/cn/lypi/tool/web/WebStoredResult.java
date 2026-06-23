package cn.lypi.tool.web;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 表示一次 Web 工具调用保存的完整结果。
 */
public record WebStoredResult(
    String sessionId,
    String messageId,
    String responseId,
    String sourceTool,
    Optional<String> query,
    Optional<String> url,
    List<WebStoredItem> items,
    Instant createdAt
) {
    public WebStoredResult {
        sessionId = sessionId == null ? "" : sessionId;
        messageId = messageId == null ? "" : messageId;
        responseId = responseId == null ? "" : responseId;
        sourceTool = sourceTool == null ? "" : sourceTool;
        query = query == null ? Optional.empty() : query;
        url = url == null ? Optional.empty() : url;
        items = items == null ? List.of() : List.copyOf(items);
        createdAt = createdAt == null ? Instant.EPOCH : createdAt;
    }

    WebStoredResult withResponseId(String newResponseId) {
        return new WebStoredResult(
            sessionId,
            messageId,
            newResponseId,
            sourceTool,
            query,
            url,
            items,
            createdAt
        );
    }

    WebStoredResult withItems(List<WebStoredItem> newItems) {
        return new WebStoredResult(
            sessionId,
            messageId,
            responseId,
            sourceTool,
            query,
            url,
            newItems,
            createdAt
        );
    }
}
