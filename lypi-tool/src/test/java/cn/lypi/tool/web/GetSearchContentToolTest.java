package cn.lypi.tool.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.security.PermissionRuntimeState;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseContext;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class GetSearchContentToolTest {
    @Test
    void inputSchemaExposesRetrievalFields() {
        GetSearchContentTool tool = new GetSearchContentTool(storeWith(sampleResult()));

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) tool.inputSchema().value().get("properties");

        assertTrue(properties.containsKey("responseId"));
        assertTrue(properties.containsKey("url"));
        assertTrue(properties.containsKey("urlIndex"));
        assertTrue(properties.containsKey("query"));
        assertTrue(properties.containsKey("queryIndex"));
        assertTrue(properties.containsKey("maxChars"));
    }

    @Test
    void contentToolIsReadOnlyConcurrencySafeAndAllowed() {
        GetSearchContentTool tool = new GetSearchContentTool(storeWith(sampleResult()));

        assertTrue(tool.isReadOnly(Map.of("responseId", "web_1")));
        assertTrue(tool.isConcurrencySafe(Map.of("responseId", "web_1")));
        assertFalse(tool.isDestructive(Map.of("responseId", "web_1")));
        assertEquals(
            PermissionBehavior.ALLOW,
            tool.checkPermissions(Map.of("responseId", "web_1"), context()).behavior()
        );
    }

    @Test
    void retrievesContentByResponseIdAndUrlIndex() {
        GetSearchContentTool tool = new GetSearchContentTool(storeWith(sampleResult()));

        ToolResult<String> result = tool.execute(
            Map.of("responseId", "web_1", "urlIndex", 2),
            context(),
            progress -> {
            }
        );

        assertFalse(result.isError());
        assertTrue(result.output().contains("responseId=web_1"));
        assertTrue(result.output().contains("sourceTool=web_search"));
        assertTrue(result.output().contains("url=https://example.com/b"));
        assertTrue(result.output().contains("title=Second"));
        assertTrue(result.output().contains("content:\nsecond content"));
    }

    @Test
    void retrievesContentByResponseIdAndUrl() {
        GetSearchContentTool tool = new GetSearchContentTool(storeWith(sampleResult()));

        ToolResult<String> result = tool.execute(
            Map.of("responseId", "web_1", "url", "https://example.com/a"),
            context(),
            progress -> {
            }
        );

        assertFalse(result.isError());
        assertTrue(result.output().contains("url=https://example.com/a"));
        assertTrue(result.output().contains("first content"));
    }

    @Test
    void retrievesLatestResultByQueryAndQueryIndex() {
        GetSearchContentTool tool = new GetSearchContentTool(storeWith(sampleResult()));

        ToolResult<String> result = tool.execute(
            Map.of("query", " Java ", "queryIndex", 1),
            context(),
            progress -> {
            }
        );

        assertFalse(result.isError());
        assertTrue(result.output().contains("responseId=web_1"));
        assertTrue(result.output().contains("first content"));
    }

    @Test
    void truncatesReturnedContentByMaxChars() {
        GetSearchContentTool tool = new GetSearchContentTool(storeWith(sampleResult()));

        ToolResult<String> result = tool.execute(
            Map.of("responseId", "web_1", "urlIndex", 1, "maxChars", 5),
            context(),
            progress -> {
            }
        );

        assertFalse(result.isError());
        assertTrue(result.output().contains("truncated=true"));
        assertTrue(result.output().contains("content:\nfirst"));
        assertFalse(result.output().contains("first content"));
    }

    @Test
    void returnsErrorWhenResultIsMissing() {
        GetSearchContentTool tool = new GetSearchContentTool(storeWith(sampleResult()));

        ToolResult<String> result = tool.execute(
            Map.of("responseId", "missing"),
            context(),
            progress -> {
            }
        );

        assertTrue(result.isError());
        assertTrue(result.output().contains("未找到"));
    }

    private WebStoredResult sampleResult() {
        return new WebStoredResult(
            "session",
            "message",
            "web_1",
            "web_search",
            Optional.of("java"),
            Optional.empty(),
            List.of(
                item("https://example.com/a", "First", "first content"),
                item("https://example.com/b", "Second", "second content")
            ),
            Instant.parse("2026-06-23T00:00:00Z")
        );
    }

    private WebStoredItem item(String url, String title, String content) {
        return new WebStoredItem(
            url,
            Optional.of(title),
            Optional.of("snippet"),
            content,
            Optional.of("markdown"),
            false,
            Optional.of("search")
        );
    }

    private WebResultStore storeWith(WebStoredResult result) {
        return new WebResultStore() {
            @Override
            public WebStoredResult save(WebStoredResult result) {
                return result;
            }

            @Override
            public Optional<WebStoredResult> findByResponseId(String sessionId, String responseId) {
                if (result.sessionId().equals(sessionId) && result.responseId().equals(responseId)) {
                    return Optional.of(result);
                }
                return Optional.empty();
            }

            @Override
            public Optional<WebStoredResult> findLatestByQuery(String sessionId, String query) {
                if (result.sessionId().equals(sessionId)
                    && result.query().map(value -> value.equalsIgnoreCase(query.trim())).orElse(false)) {
                    return Optional.of(result);
                }
                return Optional.empty();
            }
        };
    }

    private ToolUseContext context() {
        return new ToolUseContext(
            "session",
            "message",
            Path.of("."),
            Map.of("permissionRuntimeState", PermissionRuntimeState.fromLegacy(PermissionMode.BYPASS), "toolUseId", "toolu_1")
        );
    }
}
