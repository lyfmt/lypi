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
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionRule;
import cn.lypi.contracts.security.PermissionRuleSource;
import cn.lypi.contracts.security.PermissionRuleValue;
import cn.lypi.contracts.security.PermissionUpdate;
import cn.lypi.contracts.tool.ToolUseRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EventPublishingPermissionGateTest {
    @Test
    void publishesRequestAndDecisionEventsAroundDelegateGate() {
        RecordingEventBus events = new RecordingEventBus();
        PermissionGate delegate = (request, tool, context, decision) -> PermissionGateResult.allow();
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
        assertEquals("toolu_1", requestEvent.toolUseId());
        assertEquals("write", requestEvent.toolName());
        assertEquals("write requires approval", requestEvent.message());
        assertEquals("write {path=a.txt}", requestEvent.renderedToolUse());
        assertEquals(PermissionBehavior.ASK, requestEvent.decision().behavior());

        PermissionDecisionEvent decisionEvent = assertInstanceOf(PermissionDecisionEvent.class, events.events.get(1));
        assertEquals("write", decisionEvent.toolName());
        assertEquals("write {path=a.txt}", decisionEvent.renderedToolUse());
        assertEquals(PermissionBehavior.ALLOW, decisionEvent.decision().behavior());
    }

    @Test
    void publishesPermissionUpdateInDecisionEventWhenDelegateAllowsWithRule() {
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

        gate.request(
            new ToolUseRequest("toolu_1", "write", Map.of("path", "a.txt"), "msg_1"),
            TestTools.echo("write", List.of(), false, false, true),
            TestTools.toolContext(cn.lypi.contracts.security.PermissionMode.DEFAULT_EXECUTE),
            TestTools.decision(PermissionBehavior.ASK, "write requires approval")
        );

        PermissionDecisionEvent decisionEvent = assertInstanceOf(PermissionDecisionEvent.class, events.events.get(1));
        assertEquals(update, decisionEvent.decision().suggestedUpdate().orElseThrow());
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
}
