package cn.lypi.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.runtime.ToolRuntimeInvocation;
import cn.lypi.contracts.context.ContextBudget;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.prompt.SystemPrompt;
import cn.lypi.contracts.security.ActivePermissionProfile;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.ApprovalMode;
import cn.lypi.contracts.security.ApprovalPolicy;
import cn.lypi.contracts.security.LegacyPermissionBehavior;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.security.PermissionRuntimeState;
import cn.lypi.contracts.tool.ToolUseContext;
import cn.lypi.contracts.tool.ToolUseRequest;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
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
        assertEquals(PermissionRuntimeState.fromLegacy(PermissionMode.DEFAULT_EXECUTE), context.metadata().get("permissionRuntimeState"));
        assertEquals(PermissionMode.DEFAULT_EXECUTE, context.metadata().get("permissionMode"));
        assertEquals(AgentMode.EXECUTE, context.metadata().get("agentMode"));
        assertEquals("tr_1", context.metadata().get("traceId"));
    }

    @Test
    void usesSafeDefaultsWhenOptionsAreEmpty() {
        ToolUseContext context = new ToolRuntimeContextFactory(ToolRuntimeOptions.defaults()).create(
            new ToolUseRequest("toolu_1", "read", Map.of(), "msg_1"),
            TestTools.context(PermissionMode.DEFAULT_EXECUTE)
        );

        assertEquals("session_unknown", context.sessionId());
        assertEquals(PermissionMode.DEFAULT_EXECUTE, context.metadata().get("permissionMode"));
        assertEquals(AgentMode.EXECUTE, context.metadata().get("agentMode"));
        assertTrue(context.cwd().isAbsolute());
    }

    @Test
    void copiesAgentModeFromContextSnapshot() {
        ToolUseContext context = new ToolRuntimeContextFactory(ToolRuntimeOptions.defaults()).create(
            new ToolUseRequest("toolu_1", "read", Map.of(), "msg_1"),
            TestTools.context(AgentMode.PLAN, PermissionMode.DEFAULT_EXECUTE)
        );

        assertEquals(AgentMode.PLAN, context.metadata().get("agentMode"));
        assertEquals(PermissionMode.DEFAULT_EXECUTE, context.metadata().get("permissionMode"));
    }

    @Test
    void copiesCanonicalPermissionRuntimeStateToMetadata() {
        PermissionRuntimeState runtimeState = new PermissionRuntimeState(
            new ApprovalPolicy(ApprovalMode.NEVER),
            new ActivePermissionProfile("locked-down"),
            cn.lypi.contracts.security.PermissionProfiles.readOnly(),
            new LegacyPermissionBehavior(false, false, false),
            PermissionMode.DEFAULT_EXECUTE
        );

        ToolUseContext context = new ToolRuntimeContextFactory(ToolRuntimeOptions.defaults()).create(
            new ToolUseRequest("toolu_1", "read", Map.of(), "msg_1"),
            context(AgentMode.EXECUTE, runtimeState)
        );

        assertEquals(runtimeState, context.metadata().get("permissionRuntimeState"));
        assertEquals(PermissionMode.DEFAULT_EXECUTE, context.metadata().get("permissionMode"));
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

    private ContextSnapshot context(AgentMode agentMode, PermissionRuntimeState runtimeState) {
        return new ContextSnapshot(
            new SystemPrompt("system", List.of(), "hash"),
            List.of(),
            new ModelSelection("provider", "model", ThinkingLevel.MEDIUM),
            ThinkingLevel.MEDIUM,
            agentMode,
            runtimeState,
            new ContextBudget(
                0,
                0,
                0,
                0,
                0,
                0L,
                0L,
                BigDecimal.ZERO
            )
        );
    }
}
