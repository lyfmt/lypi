package cn.lypi.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import cn.lypi.contracts.event.AgentEvent;
import cn.lypi.contracts.event.EventBus;
import cn.lypi.contracts.event.EventConsumer;
import cn.lypi.contracts.event.EventFilter;
import cn.lypi.contracts.event.EventSubscription;
import cn.lypi.contracts.event.PermissionDecisionEvent;
import cn.lypi.contracts.event.PermissionRequestEvent;
import cn.lypi.contracts.security.ApprovalKind;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionDecisionReason;
import cn.lypi.contracts.security.PermissionOptionKind;
import cn.lypi.contracts.security.PermissionResponse;
import cn.lypi.contracts.security.PermissionRule;
import cn.lypi.contracts.security.PermissionRuleSource;
import cn.lypi.contracts.security.PermissionRuleValue;
import cn.lypi.contracts.security.PermissionUpdate;
import cn.lypi.contracts.tool.ToolUseRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class EventPublishingPermissionGateTest {
    @Test
    void publishesRequestAndDecisionEventsAroundDelegateGate() {
        RecordingEventBus events = new RecordingEventBus();
        AtomicReference<PermissionRequestEvent> requestedEvent = new AtomicReference<>();
        PermissionResponseGate delegate = requestEvent -> {
            requestedEvent.set(requestEvent);
            return new PermissionResponse(
                requestEvent.sessionId(),
                requestEvent.requestId(),
                "allow_once",
                false,
                Instant.parse("2026-06-01T12:00:01Z")
            );
        };
        EventPublishingPermissionGate gate = new EventPublishingPermissionGate(events, delegate);

        PermissionGateResult result = gate.request(
            new ToolUseRequest("toolu_1", "write", Map.of("path", "a.txt"), "msg_1"),
            TestTools.echo("write", List.of(), false, false, true),
            TestTools.toolContext(cn.lypi.contracts.security.PermissionMode.DEFAULT_EXECUTE),
            TestTools.decision(PermissionBehavior.ASK, "write requires approval")
        );

        assertEquals(PermissionGateResult.Status.ALLOW, result.status());
        assertEquals(2, events.events.size());
        PermissionRequestEvent requestEvent = assertInstanceOf(PermissionRequestEvent.class, events.events.get(0));
        assertEquals("ses_1", requestEvent.sessionId());
        assertEquals(requestEvent, requestedEvent.get());
        assertEquals("perm_toolu_1", requestEvent.requestId());
        assertEquals("toolu_1", requestEvent.toolUseId());
        assertEquals("write", requestEvent.toolName());
        assertEquals("write requires approval", requestEvent.message());
        assertEquals("write requires approval", requestEvent.displayTitle());
        assertEquals("write {path=a.txt}", requestEvent.renderedToolUse());
        assertEquals(PermissionBehavior.ASK, requestEvent.decision().behavior());
        assertEquals(PermissionBehavior.ASK, requestEvent.policyDecision().behavior());
        assertEquals("allow_once", requestEvent.defaultOptionId());
        assertEquals("deny", requestEvent.cancelOptionId());
        assertEquals(PermissionOptionKind.ALLOW_ONCE, requestEvent.options().getFirst().kind());

        PermissionDecisionEvent decisionEvent = assertInstanceOf(PermissionDecisionEvent.class, events.events.get(1));
        assertEquals(requestEvent.requestId(), decisionEvent.requestId());
        assertEquals("allow_once", decisionEvent.selectedOptionId());
        assertEquals("write", decisionEvent.toolName());
        assertEquals("write {path=a.txt}", decisionEvent.renderedToolUse());
        assertEquals(PermissionBehavior.ALLOW, decisionEvent.decision().behavior());
        assertEquals("selected", decisionEvent.metadata().get("updateStatus"));
    }

    @Test
    void rememberSelectionCarriesPendingUpdateWithoutClaimingApplied() {
        RecordingEventBus events = new RecordingEventBus();
        PermissionUpdate update = new PermissionUpdate(
            PermissionRuleSource.SESSION,
            new PermissionRule(
                PermissionRuleSource.SESSION,
                PermissionBehavior.ALLOW,
                new PermissionRuleValue("write", "*"),
                "allow once"
            )
        );
        PermissionResponseGate delegate = requestEvent -> {
            String rememberOptionId = requestEvent.options().stream()
                .filter(option -> option.kind() == PermissionOptionKind.ALLOW_AND_REMEMBER)
                .findFirst()
                .orElseThrow()
                .optionId();
            return new PermissionResponse(requestEvent.sessionId(), requestEvent.requestId(), rememberOptionId, false, Instant.now());
        };
        EventPublishingPermissionGate gate = new EventPublishingPermissionGate(events, delegate);

        gate.request(
            new ToolUseRequest("toolu_1", "write", Map.of("path", "a.txt"), "msg_1"),
            TestTools.echo("write", List.of(), false, false, true),
            TestTools.toolContext(cn.lypi.contracts.security.PermissionMode.DEFAULT_EXECUTE),
            new cn.lypi.contracts.security.PermissionDecision(
                PermissionBehavior.ASK,
                cn.lypi.contracts.security.PermissionDecisionReason.TOOL_SPECIFIC,
                "write requires approval",
                java.util.Optional.of(update),
                Map.of()
            )
        );

        PermissionDecisionEvent decisionEvent = assertInstanceOf(PermissionDecisionEvent.class, events.events.get(1));
        assertEquals(update, decisionEvent.decision().suggestedUpdate().orElseThrow());
        assertEquals(java.util.Optional.empty(), decisionEvent.appliedUpdate());
        assertEquals("pending_external_application", decisionEvent.metadata().get("updateStatus"));
    }

    @Test
    void denySelectionReturnsDenyResultAndPreservesSelectedOption() {
        RecordingEventBus events = new RecordingEventBus();
        EventPublishingPermissionGate gate = new EventPublishingPermissionGate(
            events,
            requestEvent -> new PermissionResponse(requestEvent.sessionId(), requestEvent.requestId(), "deny", false, Instant.now())
        );

        PermissionGateResult result = gate.request(
            new ToolUseRequest("toolu_1", "write", Map.of("path", "a.txt"), "msg_1"),
            TestTools.echo("write", List.of(), false, false, true),
            TestTools.toolContext(cn.lypi.contracts.security.PermissionMode.DEFAULT_EXECUTE),
            TestTools.decision(PermissionBehavior.ASK, "write requires approval")
        );

        assertEquals(PermissionGateResult.Status.DENY, result.status());
        PermissionDecisionEvent decisionEvent = assertInstanceOf(PermissionDecisionEvent.class, events.events.get(1));
        assertEquals("deny", decisionEvent.selectedOptionId());
        assertEquals(PermissionBehavior.DENY, decisionEvent.decision().behavior());
    }

    @Test
    void cancelSelectionReturnsAbortResultAndPreservesSelectedOption() {
        RecordingEventBus events = new RecordingEventBus();
        EventPublishingPermissionGate gate = new EventPublishingPermissionGate(
            events,
            requestEvent -> new PermissionResponse(requestEvent.sessionId(), requestEvent.requestId(), "cancel", true, Instant.now())
        );

        PermissionGateResult result = gate.request(
            new ToolUseRequest("toolu_1", "write", Map.of("path", "a.txt"), "msg_1"),
            TestTools.echo("write", List.of(), false, false, true),
            TestTools.toolContext(cn.lypi.contracts.security.PermissionMode.DEFAULT_EXECUTE),
            TestTools.decision(PermissionBehavior.ASK, "write requires approval")
        );

        assertEquals(PermissionGateResult.Status.ABORT, result.status());
        PermissionDecisionEvent decisionEvent = assertInstanceOf(PermissionDecisionEvent.class, events.events.get(1));
        assertEquals("deny", decisionEvent.selectedOptionId());
        assertEquals(PermissionBehavior.DENY, decisionEvent.decision().behavior());
    }

    @Test
    void highRiskDecisionPublishesDenyAsDefaultOption() {
        RecordingEventBus events = new RecordingEventBus();
        EventPublishingPermissionGate gate = new EventPublishingPermissionGate(
            events,
            requestEvent -> new PermissionResponse(requestEvent.sessionId(), requestEvent.requestId(), "deny", false, Instant.now())
        );

        gate.request(
            new ToolUseRequest("toolu_1", "bash", Map.of("command", "rm -rf target"), "msg_1"),
            TestTools.echo("bash", List.of(), false, false, true),
            TestTools.toolContext(cn.lypi.contracts.security.PermissionMode.DEFAULT_EXECUTE),
            new PermissionDecision(
                PermissionBehavior.ASK,
                PermissionDecisionReason.BASH_RISK,
                "destructive command",
                java.util.Optional.empty(),
                Map.of("riskLevel", "DESTRUCTIVE")
            )
        );

        PermissionRequestEvent requestEvent = assertInstanceOf(PermissionRequestEvent.class, events.events.get(0));
        assertEquals("deny", requestEvent.defaultOptionId());
    }

    @Test
    void unsupportedRememberTargetIsFilteredFromPublishedOptions() {
        RecordingEventBus events = new RecordingEventBus();
        PermissionUpdate update = new PermissionUpdate(
            PermissionRuleSource.PLATFORM,
            new PermissionRule(
                PermissionRuleSource.PLATFORM,
                PermissionBehavior.ALLOW,
                new PermissionRuleValue("write", "*"),
                "platform rule"
            )
        );
        EventPublishingPermissionGate gate = new EventPublishingPermissionGate(
            events,
            requestEvent -> new PermissionResponse(requestEvent.sessionId(), requestEvent.requestId(), "allow_once", false, Instant.now())
        );

        PermissionGateResult result = gate.request(
            new ToolUseRequest("toolu_1", "write", Map.of("path", "a.txt"), "msg_1"),
            TestTools.echo("write", List.of(), false, false, true),
            TestTools.toolContext(cn.lypi.contracts.security.PermissionMode.DEFAULT_EXECUTE),
            new PermissionDecision(
                PermissionBehavior.ASK,
                PermissionDecisionReason.TOOL_SPECIFIC,
                "write requires approval",
                java.util.Optional.of(update),
                Map.of()
            )
        );

        assertEquals(PermissionGateResult.Status.ALLOW, result.status());
        PermissionRequestEvent requestEvent = assertInstanceOf(PermissionRequestEvent.class, events.events.get(0));
        assertEquals(0, requestEvent.options().stream()
            .filter(option -> option.kind() == PermissionOptionKind.ALLOW_AND_REMEMBER)
            .count());
    }

    @Test
    void invalidResponseForCanonicalApprovalFallsBackToCancelInsteadOfAllowing() {
        RecordingEventBus events = new RecordingEventBus();
        EventPublishingPermissionGate gate = new EventPublishingPermissionGate(
            events,
            requestEvent -> new PermissionResponse(requestEvent.sessionId(), requestEvent.requestId(), "deny", false, Instant.now())
        );

        PermissionGateResult result = gate.request(
            new ToolUseRequest("toolu_1", "bash", Map.of("command", "rm target"), "msg_1"),
            TestTools.echo("bash", List.of(), false, false, true),
            TestTools.toolContext(cn.lypi.contracts.security.PermissionMode.DEFAULT_EXECUTE),
            new PermissionDecision(
                PermissionBehavior.ASK,
                PermissionDecisionReason.BASH_RISK,
                "command approval",
                java.util.Optional.empty(),
                Map.of("approvalKind", ApprovalKind.COMMAND)
            )
        );

        assertEquals(PermissionGateResult.Status.ABORT, result.status());
        PermissionDecisionEvent decisionEvent = assertInstanceOf(PermissionDecisionEvent.class, events.events.get(1));
        assertEquals("abort", decisionEvent.selectedOptionId());
    }

    @Test
    void mismatchedResponseRequestIdIsRejected() {
        RecordingEventBus events = new RecordingEventBus();
        EventPublishingPermissionGate gate = new EventPublishingPermissionGate(
            events,
            requestEvent -> new PermissionResponse(requestEvent.sessionId(), "perm_other", "allow_once", false, Instant.now())
        );

        PermissionGateResult result = gate.request(
            new ToolUseRequest("toolu_1", "write", Map.of("path", "a.txt"), "msg_1"),
            TestTools.echo("write", List.of(), false, false, true),
            TestTools.toolContext(cn.lypi.contracts.security.PermissionMode.DEFAULT_EXECUTE),
            TestTools.decision(PermissionBehavior.ASK, "write requires approval")
        );

        assertEquals(PermissionGateResult.Status.DENY, result.status());
        PermissionDecisionEvent decisionEvent = assertInstanceOf(PermissionDecisionEvent.class, events.events.get(1));
        assertEquals("deny", decisionEvent.selectedOptionId());
        assertEquals("response_mismatch", decisionEvent.metadata().get("updateStatus"));
    }

    @Test
    void mismatchedResponseSessionIdIsRejected() {
        RecordingEventBus events = new RecordingEventBus();
        EventPublishingPermissionGate gate = new EventPublishingPermissionGate(
            events,
            requestEvent -> new PermissionResponse("ses_other", requestEvent.requestId(), "allow_once", false, Instant.now())
        );

        PermissionGateResult result = gate.request(
            new ToolUseRequest("toolu_1", "write", Map.of("path", "a.txt"), "msg_1"),
            TestTools.echo("write", List.of(), false, false, true),
            TestTools.toolContext(cn.lypi.contracts.security.PermissionMode.DEFAULT_EXECUTE),
            TestTools.decision(PermissionBehavior.ASK, "write requires approval")
        );

        assertEquals(PermissionGateResult.Status.DENY, result.status());
        PermissionDecisionEvent decisionEvent = assertInstanceOf(PermissionDecisionEvent.class, events.events.get(1));
        assertEquals("deny", decisionEvent.selectedOptionId());
        assertEquals("response_mismatch", decisionEvent.metadata().get("updateStatus"));
    }

    @Test
    void legacyDelegateAllowWithUpdateMapsToRememberOption() {
        RecordingEventBus events = new RecordingEventBus();
        PermissionUpdate update = new PermissionUpdate(
            PermissionRuleSource.SESSION,
            new PermissionRule(
                PermissionRuleSource.SESSION,
                PermissionBehavior.ALLOW,
                new PermissionRuleValue("write", "*"),
                "allow once"
            )
        );
        PermissionGate delegate = (request, tool, context, decision) -> PermissionGateResult.allow(java.util.Optional.of(update));
        EventPublishingPermissionGate gate = new EventPublishingPermissionGate(events, delegate);

        PermissionGateResult result = gate.request(
            new ToolUseRequest("toolu_1", "write", Map.of("path", "a.txt"), "msg_1"),
            TestTools.echo("write", List.of(), false, false, true),
            TestTools.toolContext(cn.lypi.contracts.security.PermissionMode.DEFAULT_EXECUTE),
            new PermissionDecision(
                PermissionBehavior.ASK,
                PermissionDecisionReason.TOOL_SPECIFIC,
                "write requires approval",
                java.util.Optional.of(update),
                Map.of()
            )
        );

        assertEquals(PermissionGateResult.Status.ALLOW, result.status());
        PermissionDecisionEvent decisionEvent = assertInstanceOf(PermissionDecisionEvent.class, events.events.get(1));
        assertEquals("allow_remember", decisionEvent.selectedOptionId());
        assertEquals(update, decisionEvent.decision().suggestedUpdate().orElseThrow());
    }

    @Test
    void requestEventPublishFailureDoesNotBlockPermissionDecision() {
        ThrowingEventBus events = new ThrowingEventBus(1);
        AtomicReference<PermissionRequestEvent> requestedEvent = new AtomicReference<>();
        PermissionResponseGate delegate = requestEvent -> {
            requestedEvent.set(requestEvent);
            return new PermissionResponse(requestEvent.sessionId(), requestEvent.requestId(), "allow_once", false, Instant.now());
        };
        EventPublishingPermissionGate gate = new EventPublishingPermissionGate(events, delegate);

        PermissionGateResult result = gate.request(
            new ToolUseRequest("toolu_1", "write", Map.of("path", "a.txt"), "msg_1"),
            TestTools.echo("write", List.of(), false, false, true),
            TestTools.toolContext(cn.lypi.contracts.security.PermissionMode.DEFAULT_EXECUTE),
            TestTools.decision(PermissionBehavior.ASK, "write requires approval")
        );

        assertEquals(PermissionGateResult.Status.ALLOW, result.status());
        assertInstanceOf(PermissionRequestEvent.class, requestedEvent.get());
        assertEquals(1, events.events.size());
        assertInstanceOf(PermissionDecisionEvent.class, events.events.getFirst());
    }

    @Test
    void decisionEventPublishFailureDoesNotOverridePermissionDecision() {
        ThrowingEventBus events = new ThrowingEventBus(2);
        EventPublishingPermissionGate gate = new EventPublishingPermissionGate(
            events,
            requestEvent -> new PermissionResponse(requestEvent.sessionId(), requestEvent.requestId(), "allow_once", false, Instant.now())
        );

        PermissionGateResult result = gate.request(
            new ToolUseRequest("toolu_1", "write", Map.of("path", "a.txt"), "msg_1"),
            TestTools.echo("write", List.of(), false, false, true),
            TestTools.toolContext(cn.lypi.contracts.security.PermissionMode.DEFAULT_EXECUTE),
            TestTools.decision(PermissionBehavior.ASK, "write requires approval")
        );

        assertEquals(PermissionGateResult.Status.ALLOW, result.status());
        assertEquals(1, events.events.size());
        assertInstanceOf(PermissionRequestEvent.class, events.events.getFirst());
    }

    private static final class RecordingEventBus implements EventBus {
        private final List<AgentEvent> events = new ArrayList<>();

        @Override
        public void publish(AgentEvent event) {
            events.add(event);
        }

        @Override
        public EventSubscription subscribe(EventFilter filter, EventConsumer consumer) {
            return () -> {
            };
        }
    }

    private static final class ThrowingEventBus implements EventBus {
        private final List<AgentEvent> events = new ArrayList<>();
        private final AtomicInteger publishCount = new AtomicInteger();
        private final int throwingPublishNumber;

        private ThrowingEventBus(int throwingPublishNumber) {
            this.throwingPublishNumber = throwingPublishNumber;
        }

        @Override
        public void publish(AgentEvent event) {
            if (publishCount.incrementAndGet() == throwingPublishNumber) {
                throw new IllegalStateException("event bus unavailable");
            }
            events.add(event);
        }

        @Override
        public EventSubscription subscribe(EventFilter filter, EventConsumer consumer) {
            return () -> {
            };
        }
    }
}
