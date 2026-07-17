package cn.lypi.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.stream.Collectors;
import java.util.stream.IntStream;
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
            "bash echo hello",
            request.input()
        );
        ToolResult<String> raw = TestTools.result("toolu_1", "0123456789abcdef", false);
        ToolResult<String> finalResult = new ToolResultBudgeter().apply("toolu_1", "bash", raw, 4);

        reporter.end(request, context, "bash", "sh", raw, finalResult, ToolExecutionStatus.SUCCEEDED, started.startedAt());

        ToolStartEvent start = (ToolStartEvent) events.events.get(0);
        assertEquals("bash", start.toolName());
        assertEquals("bash echo hello", start.inputSummary());
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

    @Test
    void publishesBoundedSingleLineSummariesAndPreview() {
        RecordingEventBus events = new RecordingEventBus();
        ToolLifecycleReporter reporter = new ToolLifecycleReporter(ToolExecutionEventPublisher.eventBus(events));
        String rendered = "bash printf 'one\ntwo'\r\n" + "🙂".repeat(200);
        ToolUseRequest request = new ToolUseRequest("toolu_1", "bash", Map.of("command", rendered), "msg_1");
        ToolUseContext context = new ToolUseContext(
            "ses_1",
            "msg_1",
            Path.of("/workspace"),
            Map.of("turnId", "turn_1")
        );
        String output = IntStream.rangeClosed(1, 20)
            .mapToObj(line -> "line-" + line + " " + "🙂".repeat(20))
            .collect(Collectors.joining("\n"));

        ToolExecutionEventPublisher.StartedToolExecution started = reporter.start(
            request,
            context,
            "bash",
            "bash",
            rendered,
            request.input()
        );
        ToolResult<String> raw = TestTools.result("toolu_1", output, false);
        ToolResult<String> budgeted = new ToolResultBudgeter().apply("toolu_1", "bash", raw, 4);
        reporter.end(request, context, "bash", "bash", raw, budgeted, ToolExecutionStatus.SUCCEEDED, started.startedAt());

        ToolStartEvent start = (ToolStartEvent) events.events.get(0);
        assertFalse(start.inputSummary().contains("\r"));
        assertFalse(start.inputSummary().contains("\n"));
        assertTrue(codePointCount(start.inputSummary()) <= ToolEventSummaryFormatter.INPUT_MAX_CODE_POINTS);

        ToolEndEvent end = (ToolEndEvent) events.events.get(1);
        assertFalse(end.resultSummary().summary().contains("\n"));
        assertTrue(end.resultSummary().summary().endsWith("(+19 lines)"));
        assertTrue(codePointCount(end.resultSummary().summary()) <= ToolEventSummaryFormatter.RESULT_MAX_CODE_POINTS);
        String preview = end.resultRef().metadata().get("preview").toString();
        assertFalse(preview.contains("\n"));
        assertTrue(preview.endsWith("(+19 lines)"));
        assertTrue(codePointCount(preview) <= ToolEventSummaryFormatter.PREVIEW_MAX_CODE_POINTS);
    }

    private int codePointCount(String value) {
        return value.codePointCount(0, value.length());
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
