package cn.lycode.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lycode.contracts.agent.SteeringMessageSource;
import cn.lycode.contracts.common.AbortSignal;
import cn.lycode.contracts.runtime.ToolRuntimeInvocation;
import cn.lycode.contracts.context.ContextBudget;
import cn.lycode.contracts.context.ContextSnapshot;
import cn.lycode.contracts.model.ModelSelection;
import cn.lycode.contracts.model.ThinkingLevel;
import cn.lycode.contracts.prompt.SystemPrompt;
import cn.lycode.contracts.security.ActivePermissionProfile;
import cn.lycode.contracts.security.AgentMode;
import cn.lycode.contracts.security.ApprovalMode;
import cn.lycode.contracts.security.ApprovalPolicy;
import cn.lycode.contracts.security.LegacyPermissionBehavior;
import cn.lycode.contracts.security.PermissionMode;
import cn.lycode.contracts.security.PermissionRuntimeState;
import cn.lycode.contracts.tool.ToolUseContext;
import cn.lycode.contracts.tool.ToolUseRequest;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
            TestTools.context(PermissionMode.ASK)
        );

        assertEquals("ses_1", context.sessionId());
        assertEquals("msg_1", context.messageId());
        assertEquals(Path.of("/workspace"), context.cwd());
        assertEquals(PermissionRuntimeState.fromLegacy(PermissionMode.ASK), context.metadata().get("permissionRuntimeState"));
        assertEquals(PermissionMode.ASK, context.metadata().get("permissionMode"));
        assertEquals(AgentMode.EXECUTE, context.metadata().get("agentMode"));
        assertEquals("tr_1", context.metadata().get("traceId"));
    }

    @Test
    void usesSafeDefaultsWhenOptionsAreEmpty() {
        ToolUseContext context = new ToolRuntimeContextFactory(ToolRuntimeOptions.defaults()).create(
            new ToolUseRequest("toolu_1", "read", Map.of(), "msg_1"),
            TestTools.context(PermissionMode.ASK)
        );

        assertEquals("session_unknown", context.sessionId());
        assertEquals(PermissionMode.ASK, context.metadata().get("permissionMode"));
        assertEquals(AgentMode.EXECUTE, context.metadata().get("agentMode"));
        assertTrue(context.cwd().isAbsolute());
    }

    @Test
    void copiesAgentModeFromContextSnapshot() {
        ToolUseContext context = new ToolRuntimeContextFactory(ToolRuntimeOptions.defaults()).create(
            new ToolUseRequest("toolu_1", "read", Map.of(), "msg_1"),
            TestTools.context(AgentMode.PLAN, PermissionMode.ASK)
        );

        assertEquals(AgentMode.PLAN, context.metadata().get("agentMode"));
        assertEquals(PermissionMode.ASK, context.metadata().get("permissionMode"));
    }

    @Test
    void copiesCanonicalPermissionRuntimeStateToMetadata() {
        PermissionRuntimeState runtimeState = new PermissionRuntimeState(
            new ApprovalPolicy(ApprovalMode.NEVER),
            new ActivePermissionProfile("locked-down"),
            cn.lycode.contracts.security.PermissionProfiles.readOnly(),
            new LegacyPermissionBehavior(false, false, false),
            PermissionMode.ASK
        );

        ToolUseContext context = new ToolRuntimeContextFactory(ToolRuntimeOptions.defaults()).create(
            new ToolUseRequest("toolu_1", "read", Map.of(), "msg_1"),
            context(AgentMode.EXECUTE, runtimeState)
        );

        assertEquals(runtimeState, context.metadata().get("permissionRuntimeState"));
        assertEquals(PermissionMode.ASK, context.metadata().get("permissionMode"));
    }

    @Test
    void invocationOverridesStaticLifecycleOwnership() {
        ToolRuntimeOptions options = ToolRuntimeOptions.builder()
            .sessionId("session_static")
            .metadata(Map.of("turnId", "turn_static", "traceId", "tr_1"))
            .build();

        ToolUseContext context = new ToolRuntimeContextFactory(options).create(
            new ToolUseRequest("toolu_1", "read", Map.of(), "msg_1"),
            TestTools.context(PermissionMode.ASK),
            new ToolRuntimeInvocation("session_runtime", "turn_runtime", "entry_tool_call")
        );

        assertEquals("session_runtime", context.sessionId());
        assertEquals("turn_runtime", context.metadata().get("turnId"));
        assertEquals("entry_tool_call", context.metadata().get("parentEntryId"));
        assertEquals("tr_1", context.metadata().get("traceId"));
    }

    @Test
    void invocationOverridesStaticTurnActivitySignals() {
        AbortSignal staticAbort = () -> true;
        AbortSignal invocationAbort = () -> false;
        SteeringMessageSource staticSteering = Optional::empty;
        SteeringMessageSource invocationSteering = Optional::empty;
        ToolRuntimeOptions options = ToolRuntimeOptions.builder()
            .metadata(Map.of(
                ToolAbortSupport.METADATA_ABORT_SIGNAL, staticAbort,
                ToolSteeringSupport.METADATA_STEERING_MESSAGES, staticSteering
            ))
            .build();

        ToolUseContext context = new ToolRuntimeContextFactory(options).create(
            new ToolUseRequest("toolu_1", "read", Map.of(), "msg_1"),
            TestTools.context(PermissionMode.ASK),
            new ToolRuntimeInvocation(
                "session_runtime",
                "turn_runtime",
                "entry_tool_call",
                invocationAbort,
                invocationSteering
            )
        );

        assertSame(invocationAbort, ToolAbortSupport.signal(context));
        assertSame(invocationSteering, ToolSteeringSupport.source(context));
    }

    @Test
    void separatesStableWorkspaceRootFromValidatedInvocationCwd(@TempDir Path tempDir) throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path nested = Files.createDirectory(workspace.resolve("nested"));
        Path outside = Files.createDirectory(tempDir.resolve("outside"));
        Path escape = workspace.resolve("escape");
        Files.createSymbolicLink(escape, outside);
        ToolRuntimeContextFactory factory = new ToolRuntimeContextFactory(
            ToolRuntimeOptions.builder().cwd(workspace).build()
        );
        ToolUseRequest request = new ToolUseRequest("toolu_1", "read", Map.of(), "msg_1");

        ToolUseContext valid = factory.create(
            request,
            TestTools.context(PermissionMode.ASK),
            new ToolRuntimeInvocation("ses_1", "turn_1").withCwd(nested)
        );
        ToolUseContext lexicalEscape = factory.create(
            request,
            TestTools.context(PermissionMode.ASK),
            new ToolRuntimeInvocation("ses_1", "turn_1").withCwd(outside)
        );
        ToolUseContext missing = factory.create(
            request,
            TestTools.context(PermissionMode.ASK),
            new ToolRuntimeInvocation("ses_1", "turn_1").withCwd(workspace.resolve("missing"))
        );
        ToolUseContext symlinkEscape = factory.create(
            request,
            TestTools.context(PermissionMode.ASK),
            new ToolRuntimeInvocation("ses_1", "turn_1").withCwd(escape)
        );

        assertEquals(workspace, valid.workspaceRoot());
        assertEquals(nested, valid.cwd());
        assertEquals(workspace, lexicalEscape.cwd());
        assertEquals(workspace, missing.cwd());
        assertEquals(workspace, symlinkEscape.cwd());
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
