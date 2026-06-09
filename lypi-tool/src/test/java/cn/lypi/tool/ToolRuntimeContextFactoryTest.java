package cn.lypi.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.runtime.ToolRuntimeInvocation;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.tool.ToolUseContext;
import cn.lypi.contracts.tool.ToolUseRequest;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolRuntimeContextFactoryTest {
    @Test
    void buildsContextFromOptionsAndRequest() {
        ToolRuntimeOptions options = ToolRuntimeOptions.builder()
            .sessionId("ses_1")
            .cwd(Path.of("/workspace"))
            .metadata(Map.of("permissionMode", PermissionMode.BYPASS, "traceId", "tr_1"))
            .maxConcurrency(4)
            .build();

        ToolUseContext context = new ToolRuntimeContextFactory(options).create(
            new ToolUseRequest("toolu_1", "read", Map.of(), "msg_1"),
            TestTools.context(PermissionMode.DEFAULT_EXECUTE)
        );

        assertEquals("ses_1", context.sessionId());
        assertEquals("msg_1", context.messageId());
        assertEquals(Path.of("/workspace"), context.cwd());
        assertEquals(PermissionMode.BYPASS, context.metadata().get("permissionMode"));
        assertEquals("tr_1", context.metadata().get("traceId"));
    }

    @Test
    void usesSafeDefaultsWhenOptionsAreEmpty() {
        ToolUseContext context = new ToolRuntimeContextFactory(ToolRuntimeOptions.defaults()).create(
            new ToolUseRequest("toolu_1", "read", Map.of(), "msg_1"),
            TestTools.context(PermissionMode.PLAN)
        );

        assertEquals("session_unknown", context.sessionId());
        assertEquals(PermissionMode.PLAN, context.metadata().get("permissionMode"));
        assertTrue(context.cwd().isAbsolute());
    }

    @Test
    void invocationOverridesStaticLifecycleOwnership() {
        ToolRuntimeOptions options = ToolRuntimeOptions.builder()
            .sessionId("session_static")
            .metadata(Map.of("turnId", "turn_static", "traceId", "tr_1"))
            .build();

        ToolUseContext context = new ToolRuntimeContextFactory(options).create(
            new ToolUseRequest("toolu_1", "read", Map.of(), "msg_1"),
            TestTools.context(PermissionMode.DEFAULT_EXECUTE),
            new ToolRuntimeInvocation("session_runtime", "turn_runtime", "entry_tool_call")
        );

        assertEquals("session_runtime", context.sessionId());
        assertEquals("turn_runtime", context.metadata().get("turnId"));
        assertEquals("entry_tool_call", context.metadata().get("parentEntryId"));
        assertEquals("tr_1", context.metadata().get("traceId"));
    }
}
