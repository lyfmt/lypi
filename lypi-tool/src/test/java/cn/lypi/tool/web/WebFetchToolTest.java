package cn.lypi.tool.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.security.AdditionalPermissionProfile;
import cn.lypi.contracts.security.NetworkPermissionPolicy;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.security.PermissionRuntimeState;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseContext;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class WebFetchToolTest {
    @Test
    void inputSchemaExposesLocalFetchFieldsWithoutProvider() {
        WebFetchTool tool = new WebFetchTool(successFetcher());

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) tool.inputSchema().value().get("properties");

        assertEquals(java.util.List.of("url"), tool.inputSchema().value().get("required"));
        assertTrue(properties.containsKey("url"));
        assertTrue(properties.containsKey("query"));
        assertTrue(properties.containsKey("format"));
        assertTrue(properties.containsKey("maxChars"));
        assertFalse(properties.containsKey("provider"));
    }

    @Test
    void fetchToolWritesCacheAndRunsSerially() {
        WebFetchTool tool = new WebFetchTool(successFetcher());

        assertFalse(tool.isReadOnly(Map.of("url", "https://example.com/doc")));
        assertFalse(tool.isConcurrencySafe(Map.of("url", "https://example.com/doc")));
        assertFalse(tool.isDestructive(Map.of("url", "https://example.com/doc")));
    }

    @Test
    void rejectsUnsafeUrl() {
        WebFetchTool tool = new WebFetchTool(successFetcher());

        var validation = tool.validateInput(Map.of("url", "https://127.0.0.1"), context(PermissionMode.BYPASS));

        assertFalse(validation.valid());
        assertTrue(validation.messages().getFirst().contains("local"));
    }

    @Test
    void rejectsProviderFieldBecauseFetchIsLocal() {
        WebFetchTool tool = new WebFetchTool(successFetcher());

        var validation = tool.validateInput(
            Map.of("url", "https://example.com/doc", "provider", "tavily"),
            context(PermissionMode.BYPASS)
        );

        assertFalse(validation.valid());
        assertTrue(validation.messages().getFirst().contains("provider"));
        assertTrue(validation.messages().getFirst().contains("不支持"));
    }

    @Test
    void asksWithDomainMetadataWhenNetworkRestricted() {
        WebFetchTool tool = new WebFetchTool(successFetcher());

        var decision = tool.checkPermissions(Map.of("url", "https://example.com/doc"), context(PermissionMode.DEFAULT_EXECUTE));

        assertEquals(PermissionBehavior.ASK, decision.behavior());
        assertEquals("example.com", decision.metadata().get("domain"));
    }

    @Test
    void allowsWhenAdditionalNetworkPermissionWasApproved() {
        WebFetchTool tool = new WebFetchTool(successFetcher());

        var decision = tool.checkPermissions(Map.of("url", "https://example.com/doc"), contextWithAdditionalNetworkPermission());

        assertEquals(PermissionBehavior.ALLOW, decision.behavior());
        assertEquals("example.com", decision.metadata().get("domain"));
    }

    @Test
    void executesLocalFetchCleansHtmlAndTruncatesContent() {
        WebFetchTool tool = new WebFetchTool(successFetcher());

        ToolResult<String> result = tool.execute(
            Map.of("url", "https://example.com/doc", "maxChars", 18),
            context(PermissionMode.BYPASS),
            progress -> {
            }
        );

        assertFalse(result.isError());
        assertTrue(result.output().contains("source=local"));
        assertTrue(result.output().contains("url=https://example.com/doc"));
        assertTrue(result.output().contains("finalUrl=https://example.com/doc"));
        assertTrue(result.output().contains("title=Example"));
        assertTrue(result.output().contains("content:\n# Example\n\nBody t"));
        assertFalse(result.output().contains("console.log"));
    }

    @Test
    void storesFetchedContentAndRendersResponseId() {
        RecordingWebResultStore store = new RecordingWebResultStore("web_fetch_1");
        WebFetchTool tool = new WebFetchTool(successFetcher(), new WebContentCleaner(), store);

        ToolResult<String> result = tool.execute(
            Map.of("url", "https://example.com/doc", "maxChars", 40),
            context(PermissionMode.BYPASS),
            progress -> {
            }
        );

        assertFalse(result.isError());
        assertTrue(result.output().contains("responseId=web_fetch_1"));
        assertEquals("session", store.saved().sessionId());
        assertEquals("message", store.saved().messageId());
        assertEquals("web_fetch", store.saved().sourceTool());
        assertEquals(Optional.of("https://example.com/doc"), store.saved().url());
        assertEquals("https://example.com/doc", store.saved().items().getFirst().url());
        assertEquals(Optional.of("Example"), store.saved().items().getFirst().title());
        assertTrue(store.saved().items().getFirst().content().contains("Body text"));
    }

    @Test
    void rendersAndStoresJinaSourceWhenFallbackFetcherWasUsed() {
        RecordingWebResultStore store = new RecordingWebResultStore("web_fetch_1");
        WebPageFetcher fetcher = url -> new WebPageFetchResult(
            url,
            "text/markdown",
            "# Reader\n\nFallback body",
            "jina"
        );
        WebFetchTool tool = new WebFetchTool(fetcher, new WebContentCleaner(), store);

        ToolResult<String> result = tool.execute(
            Map.of("url", "https://example.com/doc", "maxChars", 100),
            context(PermissionMode.BYPASS),
            progress -> {
            }
        );

        assertFalse(result.isError());
        assertTrue(result.output().contains("source=jina"));
        assertEquals(Optional.of("jina"), store.saved().items().getFirst().source());
    }

    private WebPageFetcher successFetcher() {
        return url -> new WebPageFetchResult(
            url,
            "text/html; charset=utf-8",
            """
            <html>
              <head>
                <title>Example</title>
                <style>.hidden { display: none; }</style>
                <script>console.log('secret');</script>
              </head>
              <body>
                <h1>Example</h1>
                <p>Body text with pricing details.</p>
              </body>
            </html>
            """
        );
    }

    private ToolUseContext context(PermissionMode mode) {
        return new ToolUseContext(
            "session",
            "message",
            Path.of("."),
            Map.of("permissionRuntimeState", PermissionRuntimeState.fromLegacy(mode), "toolUseId", "toolu_1")
        );
    }

    private ToolUseContext contextWithAdditionalNetworkPermission() {
        return new ToolUseContext(
            "session",
            "message",
            Path.of("."),
            Map.of(
                "permissionRuntimeState", PermissionRuntimeState.fromLegacy(PermissionMode.DEFAULT_EXECUTE),
                "toolUseId", "toolu_1",
                "approvedAdditionalPermissions", true,
                "additionalPermissions", new AdditionalPermissionProfile(
                    Optional.empty(),
                    Optional.of(NetworkPermissionPolicy.enabled())
                )
            )
        );
    }

    private static final class RecordingWebResultStore implements WebResultStore {
        private final String responseId;
        private WebStoredResult saved;

        private RecordingWebResultStore(String responseId) {
            this.responseId = responseId;
        }

        @Override
        public WebStoredResult save(WebStoredResult result) {
            saved = result.withResponseId(responseId);
            return saved;
        }

        @Override
        public Optional<WebStoredResult> findByResponseId(String sessionId, String responseId) {
            return Optional.empty();
        }

        @Override
        public Optional<WebStoredResult> findLatestByQuery(String sessionId, String query) {
            return Optional.empty();
        }

        private WebStoredResult saved() {
            return saved;
        }
    }
}
