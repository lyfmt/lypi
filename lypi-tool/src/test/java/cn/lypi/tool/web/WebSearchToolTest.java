package cn.lypi.tool.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.common.ValidationResult;
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
    void searchToolIsReadOnlyAndConcurrencySafe() {
        WebSearchTool tool = new WebSearchTool(registry(successProvider()));

        assertTrue(tool.isReadOnly(Map.of("query", "java")));
        assertTrue(tool.isConcurrencySafe(Map.of("query", "java")));
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
    void providerFailureReturnsToolError() {
        WebSearchTool tool = new WebSearchTool(registry(new FailingSearchProvider()));

        ToolResult<String> result = tool.execute(Map.of("query", "java"), context(PermissionMode.BYPASS), progress -> {
        });

        assertTrue(result.isError());
        assertTrue(result.output().contains("provider down"));
    }

    private WebProviderRegistry registry(WebSearchProvider provider) {
        return new WebProviderRegistry("tavily", Map.of("tavily", provider), Map.of());
    }

    private WebSearchProvider successProvider() {
        return new SuccessSearchProvider();
    }

    private record SuccessSearchProvider() implements WebSearchProvider {
        @Override
        public String name() {
            return "tavily";
        }

        @Override
        public WebSearchResponse search(WebSearchRequest request) {
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
}
