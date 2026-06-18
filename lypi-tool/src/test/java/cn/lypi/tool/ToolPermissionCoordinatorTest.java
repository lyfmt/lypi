package cn.lypi.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionDecisionReason;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.security.PermissionRule;
import cn.lypi.contracts.security.PermissionRuleSource;
import cn.lypi.contracts.security.PermissionRuleValue;
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
    void bypassAllowsAskWithoutCallingGate() {
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

        assertTrue(result.allowed());
        assertEquals(0, gateCalls.get());
    }

    @Test
    void bypassStillPromptsForStrictAutoReviewAsk() {
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
            context(PermissionMode.BYPASS)
        );

        assertTrue(result.allowed());
        assertEquals(1, gateCalls.get());
    }

    @Test
    void defaultSandboxBashDoesNotBypassStrictAutoReviewAsk() {
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
            context(PermissionMode.BYPASS)
        );

        assertTrue(result.allowed());
        assertEquals(1, gateCalls.get());
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
        return new ToolUseContext(
            "ses_1",
            "msg_1",
            Path.of("/workspace"),
            Map.of(
                ToolRuntimeContextFactory.METADATA_AGENT_MODE,
                AgentMode.EXECUTE,
                ToolRuntimeContextFactory.METADATA_PERMISSION_MODE,
                permissionMode
            )
        );
    }
}
