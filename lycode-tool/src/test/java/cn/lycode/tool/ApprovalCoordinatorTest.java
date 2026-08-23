package cn.lycode.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lycode.contracts.event.EventBus;
import cn.lycode.contracts.event.EventConsumer;
import cn.lycode.contracts.event.EventFilter;
import cn.lycode.contracts.event.EventSubscription;
import cn.lycode.contracts.event.PermissionRequestEvent;
import cn.lycode.contracts.security.ActivePermissionProfile;
import cn.lycode.contracts.security.AdditionalPermissionProfile;
import cn.lycode.contracts.security.ApprovalKind;
import cn.lycode.contracts.security.ApprovalMode;
import cn.lycode.contracts.security.ApprovalPolicy;
import cn.lycode.contracts.security.LegacyPermissionBehavior;
import cn.lycode.contracts.security.PermissionBehavior;
import cn.lycode.contracts.security.PermissionDecision;
import cn.lycode.contracts.security.PermissionDecisionReason;
import cn.lycode.contracts.security.PermissionMode;
import cn.lycode.contracts.security.PermissionOptionKind;
import cn.lycode.contracts.security.PermissionResponse;
import cn.lycode.contracts.security.PermissionRule;
import cn.lycode.contracts.security.PermissionRuleSource;
import cn.lycode.contracts.security.PermissionRuleValue;
import cn.lycode.contracts.security.PermissionRuntimeState;
import cn.lycode.contracts.security.PermissionUpdate;
import cn.lycode.contracts.security.ReviewDecision;
import cn.lycode.contracts.tool.ToolUseContext;
import cn.lycode.contracts.tool.ToolUseRequest;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ApprovalCoordinatorTest {
    @Test
    void askOnRequestPolicyCallsGateWithStructuredApprovalRequest() {
        AtomicReference<PermissionRequestEvent> capturedEvent = new AtomicReference<>();
        EventPublishingPermissionGate gate = responseGate(capturedEvent, "approved");
        ApprovalCoordinator coordinator = coordinator(
            gate,
            PermissionUpdateStore.noop(),
            List.of()
        );
        PermissionDecision decision = askDecision(Optional.empty(), Map.of("approvalKind", ApprovalKind.COMMAND));

        PermissionGateResult result = coordinator.resolve(
            request("bash", Map.of("command", "git status")),
            TestTools.echo("bash", List.of(), false, false, true),
            context(runtimeState(ApprovalMode.ON_REQUEST)),
            decision
        );

        assertEquals(PermissionGateResult.Status.ALLOW, result.status());
        PermissionRequestEvent event = capturedEvent.get();
        assertEquals("perm_toolu_1", event.requestId());
        assertEquals("bash", event.toolName());
        assertEquals(ApprovalKind.COMMAND, event.approvalKind());
        assertTrue(event.availableDecisions().contains(ReviewDecision.APPROVED));
        assertEquals("approved", event.defaultOptionId());
    }

    @Test
    void askModeCallsGateRegardlessOfLegacyApprovalPolicy() {
        AtomicInteger gateCalls = new AtomicInteger();
        ApprovalCoordinator coordinator = coordinator(
            (request, tool, context, decision) -> {
                gateCalls.incrementAndGet();
                return PermissionGateResult.allow();
            },
            PermissionUpdateStore.noop(),
            List.of()
        );

        PermissionGateResult result = coordinator.resolve(
            request("bash", Map.of("command", "git status")),
            TestTools.echo("bash", List.of(), false, false, true),
            context(runtimeState(ApprovalMode.NEVER)),
            askDecision(Optional.empty(), Map.of("approvalKind", ApprovalKind.COMMAND))
        );

        assertEquals(PermissionGateResult.Status.ALLOW, result.status());
        assertEquals(1, gateCalls.get());
    }

    @Test
    void denyingGateExplainsUnavailableAskApprovalChannel() {
        ApprovalCoordinator coordinator = coordinator(
            PermissionGate.denying(),
            PermissionUpdateStore.noop(),
            List.of()
        );
        PermissionDecision decision = new PermissionDecision(
            PermissionBehavior.ASK,
            PermissionDecisionReason.BASH_RISK,
            "Bash 命令无法静态确认风险，需要用户确认。",
            Optional.empty(),
            Map.of("approvalKind", ApprovalKind.COMMAND)
        );

        PermissionGateResult result = coordinator.resolve(
            request("bash", Map.of("command", "echo $(id)")),
            TestTools.echo("bash", List.of(), false, false, true),
            context(runtimeState(ApprovalMode.NEVER)),
            decision
        );

        assertEquals(PermissionGateResult.Status.DENY, result.status());
        assertTrue(result.message().orElseThrow().contains("ASK 没有可用审批通道"));
        assertTrue(result.message().orElseThrow().contains(decision.message()));
    }

    @Test
    void denyingGateExplainsUnavailableAskForAdditionalPermissions() {
        ApprovalCoordinator coordinator = coordinator(
            PermissionGate.denying(),
            PermissionUpdateStore.noop(),
            List.of()
        );

        PermissionGateResult result = coordinator.resolveAdditionalPermissions(
            request("request_permissions", Map.of("reason", "need write access")),
            TestTools.echo("request_permissions", List.of(), false, false, false),
            context(runtimeState(ApprovalMode.NEVER)),
            "need write access",
            AdditionalPermissionProfile.empty()
        );

        assertEquals(PermissionGateResult.Status.DENY, result.status());
        assertTrue(result.message().orElseThrow().contains("ASK 没有可用审批通道"));
        assertTrue(result.message().orElseThrow().contains("need write access"));
    }

    @Test
    void legacyOnlyBypassModeAllowsWithoutCallingGate() {
        AtomicInteger gateCalls = new AtomicInteger();
        ApprovalCoordinator coordinator = coordinator(
            (request, tool, context, decision) -> {
                gateCalls.incrementAndGet();
                return PermissionGateResult.allow();
            },
            PermissionUpdateStore.noop(),
            List.of()
        );

        PermissionGateResult result = coordinator.resolve(
            request("bash", Map.of("command", "git status")),
            TestTools.echo("bash", List.of(), false, false, true),
            legacyOnlyContext(PermissionMode.BYPASS),
            askDecision(Optional.empty(), Map.of("approvalKind", ApprovalKind.COMMAND))
        );

        assertEquals(PermissionGateResult.Status.ALLOW, result.status());
        assertEquals(0, gateCalls.get());
    }

    @Test
    void approvedExecPolicyAmendmentAppliesUpdateToStoreAndRuntimeRules() {
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
        ApprovalCoordinator coordinator = coordinator(
            (request, tool, context, decision) -> PermissionGateResult.allow(Optional.of(update)),
            stored::add,
            runtimeRules
        );

        PermissionGateResult result = coordinator.resolve(
            request("bash", Map.of("command", "mvn test", "prefix_rule", List.of("mvn", "test"))),
            TestTools.echo("bash", List.of(), false, false, true),
            context(runtimeState(ApprovalMode.ON_REQUEST)),
            askDecision(Optional.of(update), Map.of("approvalKind", ApprovalKind.COMMAND))
        );

        assertEquals(PermissionGateResult.Status.ALLOW, result.status());
        assertEquals(List.of(update), stored);
        assertEquals(List.of(update.rule()), runtimeRules);
    }

    @Test
    void additionalPermissionsApprovalUsesApprovedDecisionOnly() {
        AtomicReference<PermissionRequestEvent> capturedEvent = new AtomicReference<>();
        EventPublishingPermissionGate gate = responseGate(capturedEvent, "approved");
        ApprovalCoordinator coordinator = coordinator(
            gate,
            PermissionUpdateStore.noop(),
            List.of()
        );
        AdditionalPermissionProfile additionalPermissions = AdditionalPermissionProfile.empty();

        PermissionGateResult result = coordinator.resolveAdditionalPermissions(
            request("request_permissions", Map.of("reason", "need write access")),
            TestTools.echo("request_permissions", List.of(), false, false, false),
            context(runtimeState(ApprovalMode.ON_REQUEST)),
            "need write access",
            additionalPermissions
        );

        assertEquals(PermissionGateResult.Status.ALLOW, result.status());
        PermissionRequestEvent event = capturedEvent.get();
        assertEquals(ApprovalKind.REQUEST_PERMISSIONS, event.approvalKind());
        assertEquals(Optional.of(additionalPermissions), event.additionalPermissions());
        assertEquals(List.of(ReviewDecision.APPROVED, ReviewDecision.ABORT), event.availableDecisions());
        assertEquals(1, event.options().stream()
            .filter(option -> option.kind() == PermissionOptionKind.ALLOW_ONCE)
            .count());
    }

    private EventPublishingPermissionGate responseGate(
        AtomicReference<PermissionRequestEvent> capturedEvent,
        String selectedOptionId
    ) {
        return new EventPublishingPermissionGate(new NoopEventBus(), requestEvent -> {
            capturedEvent.set(requestEvent);
            return new PermissionResponse(
                requestEvent.sessionId(),
                requestEvent.requestId(),
                selectedOptionId,
                false,
                Instant.now()
            );
        });
    }

    private ApprovalCoordinator coordinator(
        PermissionGate gate,
        PermissionUpdateStore store,
        List<PermissionRule> runtimeRules
    ) {
        return new ApprovalCoordinator(gate, store, runtimeRules, new ApprovalRequestFactory());
    }

    private ToolUseRequest request(String toolName, Map<String, Object> input) {
        return new ToolUseRequest("toolu_1", toolName, input, "msg_1");
    }

    private ToolUseContext context(PermissionRuntimeState runtimeState) {
        return new ToolUseContext(
            "ses_1",
            "msg_1",
            Path.of("/workspace"),
            Map.of(
                ToolRuntimeContextFactory.METADATA_PERMISSION_MODE,
                runtimeState.mode(),
                ToolRuntimeContextFactory.METADATA_PERMISSION_RUNTIME_STATE,
                runtimeState
            )
        );
    }

    private ToolUseContext legacyOnlyContext(PermissionMode permissionMode) {
        return new ToolUseContext(
            "ses_1",
            "msg_1",
            Path.of("/workspace"),
            Map.of(ToolRuntimeContextFactory.METADATA_PERMISSION_MODE, permissionMode)
        );
    }

    private PermissionRuntimeState runtimeState(ApprovalMode approvalMode) {
        return new PermissionRuntimeState(
            new ApprovalPolicy(approvalMode),
            new ActivePermissionProfile(":workspace"),
            cn.lycode.contracts.security.PermissionProfiles.workspace(),
            new LegacyPermissionBehavior(false, false, true),
            PermissionMode.ASK
        );
    }

    private PermissionDecision askDecision(Optional<PermissionUpdate> update, Map<String, Object> metadata) {
        return new PermissionDecision(
            PermissionBehavior.ASK,
            PermissionDecisionReason.TOOL_SPECIFIC,
            "requires approval",
            update,
            metadata
        );
    }

    private static final class NoopEventBus implements EventBus {
        @Override
        public void publish(cn.lycode.contracts.event.AgentEvent event) {
        }

        @Override
        public EventSubscription subscribe(EventFilter filter, EventConsumer consumer) {
            return () -> {
            };
        }
    }
}
