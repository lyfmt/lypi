package cn.lypi.tool.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.security.PermissionRuntimeState;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseContext;
import cn.lypi.contracts.web.WebFetchResponse;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class WebFetchToolTest {
    @Test
    void inputSchemaExposesFetchFields() {
        WebFetchTool tool = new WebFetchTool(registry(successProvider()));

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) tool.inputSchema().value().get("properties");

        assertEquals(java.util.List.of("url"), tool.inputSchema().value().get("required"));
        assertTrue(properties.containsKey("url"));
        assertTrue(properties.containsKey("query"));
        assertTrue(properties.containsKey("format"));
        assertTrue(properties.containsKey("maxChars"));
        assertTrue(properties.containsKey("provider"));
    }

    @Test
    void rejectsUnsafeUrl() {
        WebFetchTool tool = new WebFetchTool(registry(successProvider()));

        var validation = tool.validateInput(Map.of("url", "https://127.0.0.1"), context(PermissionMode.BYPASS));

        assertFalse(validation.valid());
        assertTrue(validation.messages().getFirst().contains("local"));
    }

    @Test
    void asksWithDomainMetadataWhenNetworkRestricted() {
        WebFetchTool tool = new WebFetchTool(registry(successProvider()));

        var decision = tool.checkPermissions(Map.of("url", "https://example.com/doc"), context(PermissionMode.DEFAULT_EXECUTE));

        assertEquals(PermissionBehavior.ASK, decision.behavior());
        assertEquals("example.com", decision.metadata().get("domain"));
    }

    @Test
    void executesFetchAndTruncatesContent() {
        WebFetchTool tool = new WebFetchTool(registry(successProvider()));

        ToolResult<String> result = tool.execute(
            Map.of("url", "https://example.com/doc", "maxChars", 7),
            context(PermissionMode.BYPASS),
            progress -> {
            }
        );

        assertFalse(result.isError());
        assertTrue(result.output().contains("provider=tavily"));
        assertTrue(result.output().contains("content:\n# Title"));
        assertFalse(result.output().contains("Body"));
    }

    private WebProviderRegistry registry(WebFetchProvider provider) {
        return new WebProviderRegistry("tavily", Map.of(), Map.of("tavily", provider));
    }

    private WebFetchProvider successProvider() {
        return new SuccessFetchProvider();
    }

    private record SuccessFetchProvider() implements WebFetchProvider {
        @Override
        public String name() {
            return "tavily";
        }

        @Override
        public WebFetchResponse fetch(WebFetchRequest request) {
            return new WebFetchResponse(
                "tavily",
                request.url(),
                Optional.of("Example"),
                "# Title\n\nBody",
                request.format(),
                Optional.empty(),
                Optional.empty()
            );
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
