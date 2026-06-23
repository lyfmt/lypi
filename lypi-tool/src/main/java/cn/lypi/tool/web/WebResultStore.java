package cn.lypi.tool.web;

import java.util.Optional;

/**
 * 保存和查询 Web 工具结果。
 */
public interface WebResultStore {
    String DISABLED_RESPONSE_ID = "cache_disabled";

    /**
     * 保存 Web 结果并返回带有 `responseId` 的记录。
     */
    WebStoredResult save(WebStoredResult result);

    /**
     * 按会话和 `responseId` 查找结果。
     */
    Optional<WebStoredResult> findByResponseId(String sessionId, String responseId);

    /**
     * 查找同一会话内最近一次匹配 query 的结果。
     */
    Optional<WebStoredResult> findLatestByQuery(String sessionId, String query);

    /**
     * 返回不持久化的空 store。
     */
    static WebResultStore noop() {
        return NoopWebResultStore.INSTANCE;
    }

    /**
     * 返回不持久化且读取时报错的禁用 store。
     */
    static WebResultStore disabled(String message) {
        return new DisabledWebResultStore(message);
    }

    enum NoopWebResultStore implements WebResultStore {
        INSTANCE;

        @Override
        public WebStoredResult save(WebStoredResult result) {
            return result == null ? emptyResult() : result;
        }

        @Override
        public Optional<WebStoredResult> findByResponseId(String sessionId, String responseId) {
            return Optional.empty();
        }

        @Override
        public Optional<WebStoredResult> findLatestByQuery(String sessionId, String query) {
            return Optional.empty();
        }

        private WebStoredResult emptyResult() {
            return new WebStoredResult("", "", "", "", Optional.empty(), Optional.empty(), java.util.List.of(), java.time.Instant.EPOCH);
        }
    }

    final class DisabledWebResultStore implements WebResultStore {
        private final String message;

        private DisabledWebResultStore(String message) {
            this.message = message == null || message.isBlank() ? "Web 结果缓存未启用。" : message;
        }

        @Override
        public WebStoredResult save(WebStoredResult result) {
            WebStoredResult stored = result == null ? emptyResult() : result;
            return stored.withResponseId(DISABLED_RESPONSE_ID);
        }

        @Override
        public Optional<WebStoredResult> findByResponseId(String sessionId, String responseId) {
            throw new IllegalStateException(message);
        }

        @Override
        public Optional<WebStoredResult> findLatestByQuery(String sessionId, String query) {
            throw new IllegalStateException(message);
        }

        private WebStoredResult emptyResult() {
            return new WebStoredResult("", "", "", "", Optional.empty(), Optional.empty(), java.util.List.of(), java.time.Instant.EPOCH);
        }
    }
}
