package cn.lypi.tool.web;

import java.util.Optional;

/**
 * 保存和查询 Web 工具结果。
 */
public interface WebResultStore {
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
}
