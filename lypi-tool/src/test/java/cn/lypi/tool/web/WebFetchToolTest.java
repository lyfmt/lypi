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
}
