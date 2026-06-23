package cn.lypi.tool.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.common.ValidationResult;
import cn.lypi.contracts.security.AdditionalPermissionProfile;
import cn.lypi.contracts.security.NetworkPermissionPolicy;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.security.PermissionRuntimeState;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseContext;
import cn.lypi.contracts.web.WebSearchResponse;
import cn.lypi.contracts.web.WebSearchResult;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class WebSearchToolTest {
    @Test
    void inputSchemaExposesSearchFields() {
        WebSearchTool tool = new WebSearchTool(registry(successProvider()));

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) tool.inputSchema().value().get("properties");

        assertEquals(List.of("query"), tool.inputSchema().value().get("required"));
        assertTrue(properties.containsKey("query"));
        assertTrue(properties.containsKey("maxResults"));
        assertTrue(properties.containsKey("allowedDomains"));
        assertTrue(properties.containsKey("blockedDomains"));
        assertTrue(properties.containsKey("recency"));
        assertTrue(properties.containsKey("provider"));
        assertTrue(properties.containsKey("includeAnswer"));
    }

    @Test
    void configuredMaxResultsControlsSchemaAndDefaultInput() {
        AtomicReference<WebSearchRequest> capturedRequest = new AtomicReference<>();
        WebSearchTool tool = new WebSearchTool(registry(capturingProvider(capturedRequest)), 7);

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) tool.inputSchema().value().get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> maxResults = (Map<String, Object>) properties.get("maxResults");
        ToolResult<String> result = tool.execute(Map.of("query", "java"), context(PermissionMode.BYPASS), progress -> {
        });

        assertEquals(7, maxResults.get("maximum"));
        assertEquals(7, capturedRequest.get().maxResults());
    }

    @Test
    void searchToolWritesCacheAndRunsSerially() {
        WebSearchTool tool = new WebSearchTool(registry(successProvider()));

        assertFalse(tool.isReadOnly(Map.of("query", "java")));
        assertFalse(tool.isConcurrencySafe(Map.of("query", "java")));
        assertFalse(tool.isDestructive(Map.of("query", "java")));
    }

    @Test
    void validatesSearchInput() {
        WebSearchTool tool = new WebSearchTool(registry(successProvider()));

        ValidationResult blank = tool.validateInput(Map.of("query", " "), context(PermissionMode.BYPASS));
        ValidationResult valid = tool.validateInput(Map.of("query", "java"), context(PermissionMode.BYPASS));

        assertFalse(blank.valid());
        assertTrue(blank.messages().getFirst().contains("query"));
        assertTrue(valid.valid());
    }

    @Test
    void rejectsProviderNotAvailableForSearch() {
        WebSearchTool tool = new WebSearchTool(registry(successProvider()));

        ValidationResult validation = tool.validateInput(
            Map.of("query", "java", "provider", "brave"),
            context(PermissionMode.BYPASS)
        );

        assertFalse(validation.valid());
        assertTrue(validation.messages().getFirst().contains("provider"));
        assertTrue(validation.messages().getFirst().contains("tavily"));
    }

    @Test
    void asksWhenNetworkProfileIsRestricted() {
        WebSearchTool tool = new WebSearchTool(registry(successProvider()));

        var decision = tool.checkPermissions(Map.of("query", "java"), context(PermissionMode.DEFAULT_EXECUTE));

        assertEquals(PermissionBehavior.ASK, decision.behavior());
        assertTrue(decision.message().contains("web_search"));
    }

    @Test
    void allowsWhenNetworkProfileIsEnabled() {
        WebSearchTool tool = new WebSearchTool(registry(successProvider()));

        var decision = tool.checkPermissions(Map.of("query", "java"), context(PermissionMode.BYPASS));

        assertEquals(PermissionBehavior.ALLOW, decision.behavior());
    }

    @Test
    void allowsWhenAdditionalNetworkPermissionWasApproved() {
        WebSearchTool tool = new WebSearchTool(registry(successProvider()));

        var decision = tool.checkPermissions(Map.of("query", "java"), contextWithAdditionalNetworkPermission());

        assertEquals(PermissionBehavior.ALLOW, decision.behavior());
    }

    @Test
    void executesProviderAndSerializesResult() {
        WebSearchTool tool = new WebSearchTool(registry(successProvider()));

        ToolResult<String> result = tool.execute(
            Map.of("query", "java", "maxResults", 1),
            context(PermissionMode.BYPASS),
            progress -> {
            }
        );

        assertFalse(result.isError());
        assertTrue(result.output().contains("provider=tavily"));
        assertTrue(result.output().contains("https://example.com"));
        assertTrue(result.output().contains("Example"));
    }

    @Test
    void storesSearchSnippetSeparatelyFromMissingContent() {
        RecordingWebResultStore store = new RecordingWebResultStore("web_1");
        WebSearchTool tool = new WebSearchTool(registry(successProvider()), store);

        ToolResult<String> result = tool.execute(
            Map.of("query", "java", "maxResults", 1),
            context(PermissionMode.BYPASS),
            progress -> {
            }
        );

        assertFalse(result.isError());
        assertTrue(result.output().contains("responseId=web_1"));
        assertEquals("session", store.saved().sessionId());
        assertEquals("message", store.saved().messageId());
        assertEquals("web_search", store.saved().sourceTool());
        assertEquals(Optional.of("java"), store.saved().query());
        assertEquals("https://example.com", store.saved().items().getFirst().url());
        assertEquals(Optional.of("Example"), store.saved().items().getFirst().title());
        assertEquals(Optional.of("snippet"), store.saved().items().getFirst().snippet());
        assertEquals("", store.saved().items().getFirst().content());
    }

    @Test
    void disabledCacheRendersClearRetrievalNote() {
        WebSearchTool tool = new WebSearchTool(registry(successProvider()), WebResultStore.disabled("Web 结果缓存未启用。"));

        ToolResult<String> result = tool.execute(
            Map.of("query", "java", "maxResults", 1),
            context(PermissionMode.BYPASS),
            progress -> {
            }
        );

        assertFalse(result.isError());
        assertTrue(result.output().contains("responseId=cache_disabled"));
        assertTrue(result.output().contains("cache=disabled"));
        assertTrue(result.output().contains("Web 结果缓存未启用"));
    }

    @Test
    void providerFailureReturnsToolError() {
        RecordingWebResultStore store = new RecordingWebResultStore("web_1");
        WebSearchTool tool = new WebSearchTool(registry(new FailingSearchProvider()), store);

        ToolResult<String> result = tool.execute(Map.of("query", "java"), context(PermissionMode.BYPASS), progress -> {
        });

        assertTrue(result.isError());
        assertTrue(result.output().contains("provider down"));
        assertFalse(store.wasSaved());
    }

    @Test
    void fallsBackWhenDefaultProviderFails() {
        WebSearchTool tool = new WebSearchTool(new WebProviderRegistry(
            "tavily",
            Map.of(
                "tavily", new FailingSearchProvider(),
                "brave", new NamedSuccessSearchProvider("brave")
            )
        ));

        ToolResult<String> result = tool.execute(Map.of("query", "java"), context(PermissionMode.BYPASS), progress -> {
        });

        assertFalse(result.isError());
        assertTrue(result.output().contains("provider=brave"));
    }

    @Test
    void doesNotFallbackWhenProviderWasRequested() {
        WebSearchTool tool = new WebSearchTool(new WebProviderRegistry(
            "brave",
            Map.of(
                "tavily", new FailingSearchProvider(),
                "brave", new NamedSuccessSearchProvider("brave")
            )
        ));

        ToolResult<String> result = tool.execute(
            Map.of("query", "java", "provider", "tavily"),
            context(PermissionMode.BYPASS),
            progress -> {
            }
        );

        assertTrue(result.isError());
        assertTrue(result.output().contains("provider down"));
    }

    private WebProviderRegistry registry(WebSearchProvider provider) {
        return new WebProviderRegistry("tavily", Map.of("tavily", provider));
    }

    private WebSearchProvider successProvider() {
        return new SuccessSearchProvider();
    }

    private WebSearchProvider capturingProvider(AtomicReference<WebSearchRequest> capturedRequest) {
        return new CapturingSearchProvider(capturedRequest);
    }

    private final class SuccessSearchProvider implements WebSearchProvider {
        @Override
        public String name() {
            return "tavily";
        }

        @Override
        public WebSearchResponse search(WebSearchRequest request) {
            return searchResponse(request);
        }
    }

    private final class CapturingSearchProvider implements WebSearchProvider {
        private final AtomicReference<WebSearchRequest> capturedRequest;

        private CapturingSearchProvider(AtomicReference<WebSearchRequest> capturedRequest) {
            this.capturedRequest = capturedRequest;
        }

        @Override
        public String name() {
            return "tavily";
        }

        @Override
        public WebSearchResponse search(WebSearchRequest request) {
            capturedRequest.set(request);
            return searchResponse(request);
        }
    }

    private final class NamedSuccessSearchProvider implements WebSearchProvider {
        private final String name;

        private NamedSuccessSearchProvider(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public WebSearchResponse search(WebSearchRequest request) {
            return new WebSearchResponse(
                name,
                request.query(),
                Optional.empty(),
                List.of(),
                Optional.empty()
            );
        }
    }

    private WebSearchResponse searchResponse(WebSearchRequest request) {
        return new WebSearchResponse(
            "tavily",
            request.query(),
            Optional.of("answer"),
            List.of(new WebSearchResult(
                "Example",
                "https://example.com",
                Optional.of("snippet"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(1.0d),
                Optional.empty()
            )),
            Optional.empty()
        );
    }

    private record FailingSearchProvider() implements WebSearchProvider {
        @Override
        public String name() {
            return "tavily";
        }

        @Override
        public WebSearchResponse search(WebSearchRequest request) {
            throw new WebProviderException("provider down");
        }
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

        private boolean wasSaved() {
            return saved != null;
        }

        private WebStoredResult saved() {
            return saved;
        }
    }
}
