package cn.lypi.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.event.AgentEvent;
import cn.lypi.contracts.event.EventBus;
import cn.lypi.contracts.event.EventConsumer;
import cn.lypi.contracts.event.EventFilter;
import cn.lypi.contracts.event.EventSubscription;
import cn.lypi.contracts.event.ToolEndEvent;
import cn.lypi.contracts.event.ToolStartEvent;
import cn.lypi.contracts.tool.ToolExecutionStatus;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseContext;
import cn.lypi.contracts.tool.ToolUseRequest;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class ToolLifecycleReporterTest {
    @Test
    void publishesStartAndBudgetedEndWithOriginalToolMetadata() {
        RecordingEventBus events = new RecordingEventBus();
        ToolLifecycleReporter reporter = new ToolLifecycleReporter(ToolExecutionEventPublisher.eventBus(events));
        ToolUseRequest request = new ToolUseRequest("toolu_1", "bash", Map.of("text", "hello"), "msg_1");
        ToolUseContext context = new ToolUseContext(
            "ses_1",
            "msg_1",
            Path.of("/workspace"),
            Map.of("turnId", "turn_1")
        );

        ToolExecutionEventPublisher.StartedToolExecution started = reporter.start(
            request,
            context,
            "bash",
            "sh",
            request.input()
        );
        ToolResult<String> raw = TestTools.result("toolu_1", "0123456789abcdef", false);
        ToolResult<String> finalResult = new ToolResultBudgeter().apply("toolu_1", "bash", raw, 4);

        reporter.end(request, context, "bash", "sh", raw, finalResult, ToolExecutionStatus.SUCCEEDED, started.startedAt());

        ToolStartEvent start = (ToolStartEvent) events.events.get(0);
        assertEquals("bash", start.toolName());
        assertEquals("sh", start.inputMetadata().get("originalToolName"));
        ToolEndEvent end = (ToolEndEvent) events.events.get(1);
        assertEquals(ToolExecutionStatus.SUCCEEDED, end.status());
        assertEquals("bash", end.metadata().get("toolName"));
        assertEquals("sh", end.metadata().get("originalToolName"));
        assertEquals("sh", end.resultSummary().metadata().get("originalToolName"));
        assertNotNull(end.resultRef());
        assertTrue(end.resultRef().metadata().containsKey("truncated"));
    }

    @Test
    void omitsResultRefWhenResultIsNotBudgeted() {
        ToolLifecycleReporter reporter = new ToolLifecycleReporter(ToolExecutionEventPublisher.noop());
        ToolResult<String> result = TestTools.result("toolu_1", "ok", false);

        assertNull(reporter.resultRef("ses_1", "toolu_1", "read", result, false));
    }

    private static final class RecordingEventBus implements EventBus {
        private final List<AgentEvent> events = new CopyOnWriteArrayList<>();

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
