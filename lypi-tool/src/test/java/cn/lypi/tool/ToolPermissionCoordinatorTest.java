package cn.lypi.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.AdditionalPermissionProfile;
import cn.lypi.contracts.security.ApprovalKind;
import cn.lypi.contracts.security.ApprovalMode;
import cn.lypi.contracts.security.ApprovalPolicy;
import cn.lypi.contracts.security.FileSystemAccessMode;
import cn.lypi.contracts.security.FileSystemPath;
import cn.lypi.contracts.security.FileSystemPermissionEntry;
import cn.lypi.contracts.security.FileSystemPermissionPolicy;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionDecisionReason;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.security.PermissionRule;
import cn.lypi.contracts.security.PermissionRuleSource;
import cn.lypi.contracts.security.PermissionRuleValue;
import cn.lypi.contracts.security.PermissionRuntimeState;
import cn.lypi.contracts.security.PermissionUpdate;
import cn.lypi.contracts.tool.Tool;
import cn.lypi.contracts.tool.ToolUseContext;
import cn.lypi.contracts.tool.ToolUseRequest;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ToolPermissionCoordinatorTest {
    @Test
    void allowsToolSpecificAskWhenGateAllows() {
        AtomicReference<PermissionDecision> requestedDecision = new AtomicReference<>();
        ToolPermissionCoordinator coordinator = coordinator(
            (request, context) -> TestTools.decision(PermissionBehavior.ALLOW, "security allow"),
            (request, tool, context, decision) -> {
                requestedDecision.set(decision);
                return PermissionGateResult.allow();
            },
            PermissionUpdateStore.noop(),
            List.of()
        );
        Tool<Map<String, Object>, String> tool = TestTools.permission("write", PermissionBehavior.ASK);

        ToolPermissionCoordinator.Result result = coordinator.authorize(
            request("write", Map.of("text", "ok")),
            tool,
            Map.of("text", "ok"),
            context(PermissionMode.DEFAULT_EXECUTE)
        );

        assertTrue(result.allowed());
        assertEquals(PermissionBehavior.ASK, requestedDecision.get().behavior());
    }

    @Test
    void neverPolicyDeniesAskWithoutCallingGate() {
        AtomicInteger gateCalls = new AtomicInteger();
        ToolPermissionCoordinator coordinator = coordinator(
            (request, context) -> TestTools.decision(PermissionBehavior.ASK, "security ask"),
            (request, tool, context, decision) -> {
                gateCalls.incrementAndGet();
                return PermissionGateResult.deny("should not ask");
            },
            PermissionUpdateStore.noop(),
            List.of()
        );

        ToolPermissionCoordinator.Result result = coordinator.authorize(
            request("write", Map.of("text", "ok")),
            TestTools.permission("write", PermissionBehavior.ALLOW),
            Map.of("text", "ok"),
            context(PermissionMode.BYPASS)
        );

        assertEquals(PermissionGateResult.Status.DENY, result.gateResult().status());
        assertEquals(0, gateCalls.get());
    }

    @Test
    void onRequestPolicyStillPromptsForStrictAutoReviewAsk() {
        AtomicInteger gateCalls = new AtomicInteger();
        ToolPermissionCoordinator coordinator = coordinator(
            (request, context) -> new PermissionDecision(
                PermissionBehavior.ASK,
                PermissionDecisionReason.SANDBOX_POLICY,
                "strictAutoReview 要求本轮后续命令先进入人工 review。",
                Optional.empty(),
                Map.of("strictAutoReview", true)
            ),
            (request, tool, context, decision) -> {
                gateCalls.incrementAndGet();
                return PermissionGateResult.allow();
            },
            PermissionUpdateStore.noop(),
            List.of()
        );

        ToolPermissionCoordinator.Result result = coordinator.authorize(
            request("write", Map.of("text", "ok")),
            TestTools.permission("write", PermissionBehavior.ALLOW),
            Map.of("text", "ok"),
            context(PermissionMode.DEFAULT_EXECUTE)
        );

        assertTrue(result.allowed(), () -> result.gateResult().status() + " " + result.gateResult().message().orElse(""));
        assertEquals(1, gateCalls.get());
    }

    @Test
    void defaultSandboxBashDelegatesStrictAutoReviewAskUnderOnRequestPolicy() {
        AtomicInteger gateCalls = new AtomicInteger();
        ToolPermissionCoordinator coordinator = coordinator(
            (request, context) -> new PermissionDecision(
                PermissionBehavior.ASK,
                PermissionDecisionReason.SANDBOX_POLICY,
                "strictAutoReview 要求本轮后续命令先进入人工 review。",
                Optional.empty(),
                Map.of("strictAutoReview", true)
            ),
            (request, tool, context, decision) -> {
                gateCalls.incrementAndGet();
                return PermissionGateResult.allow();
            },
            PermissionUpdateStore.noop(),
            List.of()
        );
        Map<String, Object> input = Map.of("command", "pwd");

        ToolPermissionCoordinator.Result result = coordinator.authorize(
            request("bash", input),
            TestTools.permission("bash", PermissionBehavior.ALLOW),
            input,
            context(PermissionMode.DEFAULT_EXECUTE)
        );

        assertTrue(result.allowed());
        assertEquals(1, gateCalls.get());
    }

    @Test
    void sandboxEscalationUsesCanonicalRuntimeBehaviorInsteadOfLegacyModeMetadata() {
        ToolPermissionCoordinator coordinator = coordinator(
            (request, context) -> TestTools.decision(PermissionBehavior.ALLOW, "security allow"),
            (request, tool, context, decision) -> PermissionGateResult.deny("should not ask"),
            PermissionUpdateStore.noop(),
            List.of()
        );
        Map<String, Object> input = Map.of(
            "command", "id",
            "sandboxPermissions", "requireEscalated",
            "justification", "host inspection"
        );

        ToolPermissionCoordinator.Result result = coordinator.authorize(
            request("bash", input),
            TestTools.permission("bash", PermissionBehavior.ALLOW),
            input,
            contextWithRuntimeState(
                PermissionMode.DEFAULT_EXECUTE,
                runtimeStateWithLegacyMode(PermissionRuntimeState.fromLegacy(PermissionMode.BYPASS), PermissionMode.DEFAULT_EXECUTE)
            )
        );

        assertTrue(result.allowed(), () -> result.gateResult().status() + " " + result.gateResult().message().orElse(""));
    }

    @Test
    void defaultBashSandboxRiskUsesCanonicalRuntimeBehaviorInsteadOfLegacyModeMetadata() {
        ToolPermissionCoordinator coordinator = coordinator(
            (request, context) -> new PermissionDecision(
                PermissionBehavior.ASK,
                PermissionDecisionReason.BASH_RISK,
                "默认执行模式下 Bash 写入、网络或远端变更需要用户确认。",
                Optional.empty(),
                Map.of()
            ),
            (request, tool, context, decision) -> PermissionGateResult.deny("should not ask"),
            PermissionUpdateStore.noop(),
            List.of()
        );
        Map<String, Object> input = Map.of("command", "rm -rf build");

        ToolPermissionCoordinator.Result result = coordinator.authorize(
            request("bash", input),
            TestTools.permission("bash", PermissionBehavior.ALLOW),
            input,
            contextWithRuntimeState(
                PermissionMode.DEFAULT_EXECUTE,
                runtimeStateWithLegacyMode(PermissionRuntimeState.fromLegacy(PermissionMode.BYPASS), PermissionMode.DEFAULT_EXECUTE)
            )
        );

        assertTrue(result.allowed());
    }

    @Test
    void deniesHardSecurityDecisionBeforeGate() {
        AtomicInteger gateCalls = new AtomicInteger();
        ToolPermissionCoordinator coordinator = coordinator(
            (request, context) -> TestTools.decision(PermissionBehavior.DENY, "hard deny"),
            (request, tool, context, decision) -> {
                gateCalls.incrementAndGet();
                return PermissionGateResult.allow();
            },
            PermissionUpdateStore.noop(),
            List.of()
        );

        ToolPermissionCoordinator.Result result = coordinator.authorize(
            request("write", Map.of("text", "blocked")),
            TestTools.permission("write", PermissionBehavior.ALLOW),
            Map.of("text", "blocked"),
            context(PermissionMode.BYPASS)
        );

        assertEquals(PermissionGateResult.Status.DENY, result.gateResult().status());
        assertEquals("hard deny", result.gateResult().message().orElseThrow());
        assertEquals(0, gateCalls.get());
    }

    @Test
    void appliesAllowedPermissionUpdateToStoreAndRuntimeRules() {
        List<PermissionUpdate> stored = new ArrayList<>();
        List<PermissionRule> runtimeRules = new ArrayList<>();
        PermissionUpdate update = new PermissionUpdate(
            PermissionRuleSource.USER,
            new PermissionRule(
                PermissionRuleSource.USER,
                PermissionBehavior.ALLOW,
                new PermissionRuleValue("bash", "prefix:mvn test"),
                "允许 Maven 测试"
            )
        );
        ToolPermissionCoordinator coordinator = coordinator(
            (request, context) -> TestTools.decision(PermissionBehavior.ASK, "security ask"),
            (request, tool, context, decision) -> PermissionGateResult.allow(Optional.of(update)),
            stored::add,
            runtimeRules
        );

        ToolPermissionCoordinator.Result result = coordinator.authorize(
            request("bash", Map.of("command", "mvn test", "prefix_rule", List.of("mvn", "test"))),
            TestTools.permission("bash", PermissionBehavior.ALLOW),
            Map.of("command", "mvn test", "prefix_rule", List.of("mvn", "test")),
            context(PermissionMode.DEFAULT_EXECUTE)
        );

        assertTrue(result.allowed());
        assertEquals(List.of(update), stored);
        assertEquals(List.of(update.rule()), runtimeRules);
    }

    @Test
    void freshInlineAdditionalPermissionsPromptAndReturnApprovedPayload() {
        AtomicReference<PermissionDecision> requestedDecision = new AtomicReference<>();
        AdditionalPermissionProfile permissions = additionalWrite("/workspace/cache");
        ToolPermissionCoordinator coordinator = coordinator(
            (request, context) -> TestTools.decision(PermissionBehavior.ALLOW, "security allow"),
            (request, tool, context, decision) -> {
                requestedDecision.set(decision);
                return PermissionGateResult.allow();
            },
            PermissionUpdateStore.noop(),
            List.of()
        );
        Map<String, Object> input = Map.of(
            "command", "touch cache/out",
            "sandboxPermissions", "withAdditionalPermissions",
            "additionalPermissions", permissions
        );

        ToolPermissionCoordinator.Result result = coordinator.authorize(
            request("bash", input),
            TestTools.permission("bash", PermissionBehavior.ALLOW),
            input,
            context(PermissionMode.DEFAULT_EXECUTE)
        );

        assertTrue(result.allowed());
        assertEquals(Optional.of(permissions), result.approvedAdditionalPermissions());
        assertEquals(ApprovalKind.REQUEST_PERMISSIONS, requestedDecision.get().metadata().get("approvalKind"));
        assertEquals(permissions, requestedDecision.get().metadata().get("additionalPermissions"));
    }

    @Test
    void inlineAdditionalPermissionsMergeWithPreapprovedContextPermissions() {
        AtomicReference<PermissionDecision> requestedDecision = new AtomicReference<>();
        AdditionalPermissionProfile preapproved = additionalWrite("/workspace/cache");
        AdditionalPermissionProfile requested = additionalWrite("/workspace/output");
        ToolPermissionCoordinator coordinator = coordinator(
            (request, context) -> TestTools.decision(PermissionBehavior.ALLOW, "security allow"),
            (request, tool, context, decision) -> {
                requestedDecision.set(decision);
                return PermissionGateResult.allow();
            },
            PermissionUpdateStore.noop(),
            List.of()
        );
        Map<String, Object> input = Map.of(
            "command", "touch output/out",
            "sandboxPermissions", "withAdditionalPermissions",
            "additionalPermissions", requested
        );

        ToolPermissionCoordinator.Result result = coordinator.authorize(
            request("bash", input),
            TestTools.permission("bash", PermissionBehavior.ALLOW),
            input,
            contextWithAdditionalPermissions(PermissionMode.DEFAULT_EXECUTE, preapproved)
        );

        assertTrue(result.allowed());
        AdditionalPermissionProfile merged = result.approvedAdditionalPermissions().orElseThrow();
        assertTrue(merged.fileSystem().orElseThrow().entries().containsAll(List.of(
            preapproved.fileSystem().orElseThrow().entries().getFirst(),
            requested.fileSystem().orElseThrow().entries().getFirst()
        )));
        assertEquals(ApprovalKind.REQUEST_PERMISSIONS, requestedDecision.get().metadata().get("approvalKind"));
        assertEquals(requested, requestedDecision.get().metadata().get("additionalPermissions"));
    }

    private ToolPermissionCoordinator coordinator(
        cn.lypi.contracts.runtime.SecurityRuntimePort security,
        PermissionGate gate,
        PermissionUpdateStore store,
        List<PermissionRule> runtimeRules
    ) {
        return new ToolPermissionCoordinator(
            security,
            gate,
            store,
            runtimeRules,
            new SandboxEscalationPolicy(),
            new BashSandboxRiskPolicy()
        );
    }

    private ToolUseRequest request(String toolName, Map<String, Object> input) {
        return new ToolUseRequest("toolu_1", toolName, input, "msg_1");
    }

    private ToolUseContext context(PermissionMode permissionMode) {
        PermissionRuntimeState runtimeState = PermissionRuntimeState.fromLegacy(permissionMode);
        return new ToolUseContext(
            "ses_1",
            "msg_1",
            Path.of("/workspace"),
            Map.of(
                ToolRuntimeContextFactory.METADATA_AGENT_MODE,
                AgentMode.EXECUTE,
                ToolRuntimeContextFactory.METADATA_PERMISSION_MODE,
                permissionMode,
                ToolRuntimeContextFactory.METADATA_PERMISSION_RUNTIME_STATE,
                runtimeState
            )
        );
    }

    private ToolUseContext contextWithAdditionalPermissions(
        PermissionMode permissionMode,
        AdditionalPermissionProfile additionalPermissions
    ) {
        ToolUseContext base = context(permissionMode);
        Map<String, Object> metadata = new java.util.LinkedHashMap<>(base.metadata());
        metadata.put("additionalPermissions", additionalPermissions);
        metadata.put("approvedAdditionalPermissions", true);
        return new ToolUseContext(base.sessionId(), base.messageId(), base.cwd(), Map.copyOf(metadata));
    }

    private ToolUseContext contextWithRuntimeState(PermissionMode permissionMode, PermissionRuntimeState runtimeState) {
        ToolUseContext base = context(permissionMode);
        Map<String, Object> metadata = new java.util.LinkedHashMap<>(base.metadata());
        metadata.put(ToolRuntimeContextFactory.METADATA_PERMISSION_RUNTIME_STATE, runtimeState);
        metadata.put(ToolRuntimeContextFactory.METADATA_PERMISSION_MODE, permissionMode);
        return new ToolUseContext(base.sessionId(), base.messageId(), base.cwd(), Map.copyOf(metadata));
    }

    private PermissionRuntimeState runtimeStateWithLegacyMode(PermissionRuntimeState source, PermissionMode legacyMode) {
        return new PermissionRuntimeState(
            new ApprovalPolicy(ApprovalMode.ON_REQUEST),
            source.activePermissionProfile(),
            source.permissionProfile(),
            source.legacyBehavior(),
            legacyMode
        );
    }

    private AdditionalPermissionProfile additionalWrite(String path) {
        return new AdditionalPermissionProfile(
            Optional.of(FileSystemPermissionPolicy.restricted(List.of(
                new FileSystemPermissionEntry(
                    FileSystemPath.exactPath(path),
                    FileSystemAccessMode.WRITE
                )
            ))),
            Optional.empty()
        );
    }
}
