package cn.lypi.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.common.AbortSignal;
import cn.lypi.contracts.common.JsonSchema;
import cn.lypi.contracts.common.ProgressSink;
import cn.lypi.contracts.common.ValidationResult;
import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.ToolResultContentBlock;
import cn.lypi.contracts.event.AgentEvent;
import cn.lypi.contracts.event.EventBus;
import cn.lypi.contracts.event.EventConsumer;
import cn.lypi.contracts.event.EventFilter;
import cn.lypi.contracts.event.EventSubscription;
import cn.lypi.contracts.event.ToolEndEvent;
import cn.lypi.contracts.event.ToolStartEvent;
import cn.lypi.contracts.runtime.SecurityRuntimePort;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionDecisionReason;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.security.PermissionUpdate;
import cn.lypi.contracts.tool.InterruptBehavior;
import cn.lypi.contracts.tool.Tool;
import cn.lypi.contracts.tool.ToolExecutionStatus;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseContext;
import cn.lypi.contracts.tool.ToolUseRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class ToolRuntimePermissionAndAbortCoverageTest {
    @Test
    void keepsStableEventsForDeniedAbortThrowingAndSuccessfulBatchCalls() {
        RecordingEventBus events = new RecordingEventBus();
        PermissionGate gate = (request, tool, context, decision) -> {
            if (request.toolUseId().equals("toolu_denied")) {
                return PermissionGateResult.deny("operator denied");
            }
            if (request.toolUseId().equals("toolu_aborted")) {
                return PermissionGateResult.abort("operator aborted");
            }
            return PermissionGateResult.allow();
        };
        DefaultToolRuntime runtime = runtime(
            events,
            ToolRuntimeOptions.builder()
                .sessionId("ses_1")
                .metadata(Map.of("turnId", "turn_1"))
                .maxConcurrency(1)
                .build(),
            allowAllSecurity(),
            gate
        );
        runtime.register(askingTool("needs_permission"));
        runtime.register(throwingTool("throws"));
        runtime.register(echoTool("ok", 4096));

        List<ToolResult<?>> results = runtime.execute(List.of(
            new ToolUseRequest("toolu_denied", "needs_permission", Map.of("text", "denied"), "msg_1"),
            new ToolUseRequest("toolu_aborted", "needs_permission", Map.of("text", "aborted"), "msg_1"),
            new ToolUseRequest("toolu_throwing", "throws", Map.of("text", "boom"), "msg_1"),
            new ToolUseRequest("toolu_ok", "ok", Map.of("text", "done"), "msg_1")
        ), TestTools.context(PermissionMode.DEFAULT_EXECUTE));

        assertEquals(List.of(true, true, true, false), results.stream().map(ToolResult::isError).toList());
        assertEventOrder(events.events, List.of(
            "start:toolu_denied:needs_permission",
            "end:toolu_denied:FAILED",
            "start:toolu_aborted:needs_permission",
            "end:toolu_aborted:CANCELLED",
            "start:toolu_throwing:throws",
            "end:toolu_throwing:FAILED",
            "start:toolu_ok:ok",
            "end:toolu_ok:SUCCEEDED"
        ));
    }

    @Test
    void abortSignalPublishesCancelledEndBetweenOtherBatchCalls() {
        RecordingEventBus events = new RecordingEventBus();
        AbortSignal signal = () -> true;
        DefaultToolRuntime runtime = runtime(
            events,
            ToolRuntimeOptions.builder()
                .sessionId("ses_1")
                .metadata(Map.of("abortSignal", signal, "turnId", "turn_1"))
                .maxConcurrency(1)
                .build(),
            allowAllSecurity(),
            PermissionGate.denying()
        );
        runtime.register(echoTool("cancelled", 4096));
        runtime.register(blockingEchoTool("blocking"));

        List<ToolResult<?>> results = runtime.execute(List.of(
            new ToolUseRequest("toolu_cancelled", "cancelled", Map.of("text", "skip"), "msg_1"),
            new ToolUseRequest("toolu_blocking", "blocking", Map.of("text", "run"), "msg_1")
        ), TestTools.context(PermissionMode.DEFAULT_EXECUTE));

        assertTrue(results.get(0).isError());
        assertFalse(results.get(1).isError());
        assertEventOrder(events.events, List.of(
            "start:toolu_cancelled:cancelled",
            "end:toolu_cancelled:CANCELLED",
            "start:toolu_blocking:blocking",
            "end:toolu_blocking:SUCCEEDED"
        ));
    }

    @Test
    void longStdoutAndStderrCreateOutputRefWithoutDamagingStructuredSummary() {
        RecordingEventBus events = new RecordingEventBus();
        DefaultToolRuntime runtime = runtime(
            events,
            ToolRuntimeOptions.builder()
                .sessionId("ses_1")
                .metadata(Map.of("turnId", "turn_1"))
                .build(),
            allowAllSecurity(),
            PermissionGate.denying()
        );
        runtime.register(stdoutStderrTool("bash", 24));

        ToolResult<?> result = runtime.execute(
            List.of(new ToolUseRequest(
                "toolu_long",
                "bash",
                Map.of("stdout", "out-".repeat(20), "stderr", "err-".repeat(20), "exitCode", 7),
                "msg_1"
            )),
            TestTools.context(PermissionMode.DEFAULT_EXECUTE)
        ).getFirst();

        assertTrue(result.isError());
        ToolEndEvent end = events.toolEnds().getFirst();
        assertEquals(ToolExecutionStatus.FAILED, end.status());
        assertEquals(7, end.exitCode());
        assertEquals(7, end.resultSummary().exitCode());
        assertTrue(end.resultSummary().error());
        assertTrue(end.resultSummary().summary().contains("exitCode=7"));
        assertTrue(end.resultSummary().summary().contains("stdout:"));
        assertTrue(end.resultSummary().summary().contains("stderr:"));
        assertFalse(end.resultSummary().summary().contains("工具结果已超出预算"));
        assertNotNull(end.resultRef());
        assertEquals("toolout_ses_1_toolu_long", end.resultRef().refId());
        assertEquals("toolu_long", end.resultRef().toolUseId());
        assertEquals("bash", end.resultRef().metadata().get("toolName"));
        assertEquals(true, end.resultRef().metadata().get("truncated"));
        assertEquals("budgeted", end.resultRef().metadata().get("truncationReason"));
        assertTrue(end.resultRef().contentHash().startsWith("sha256:"));
        assertTrue(end.resultRef().byteLength() > 24);
    }

    private void assertEventOrder(List<AgentEvent> events, List<String> expected) {
        List<String> actual = new ArrayList<>();
        for (AgentEvent event : events) {
            if (event instanceof ToolStartEvent start) {
                actual.add("start:" + start.toolUseId() + ":" + start.toolName());
            }
            if (event instanceof ToolEndEvent end) {
                actual.add("end:" + end.toolUseId() + ":" + end.status());
            }
        }
        assertEquals(expected, actual);
    }

    private DefaultToolRuntime runtime(
        EventBus eventBus,
        ToolRuntimeOptions options,
        SecurityRuntimePort security,
        PermissionGate gate
    ) {
        return new DefaultToolRuntime(options, security, gate, eventBus);
    }

    private SecurityRuntimePort allowAllSecurity() {
        return (request, context) -> decision(PermissionBehavior.ALLOW, "allowed");
    }

    private Tool<Map<String, Object>, String> askingTool(String name) {
        return new BasicStringTool(name, 4096) {
            @Override
            public PermissionDecision checkPermissions(Map<String, Object> input, ToolUseContext context) {
                return decision(PermissionBehavior.ASK, "needs permission");
            }
        };
    }

    private Tool<Map<String, Object>, String> echoTool(String name, int maxResultSize) {
        return new BasicStringTool(name, maxResultSize);
    }

    private Tool<Map<String, Object>, String> blockingEchoTool(String name) {
        return new BasicStringTool(name, 4096) {
            @Override
            public InterruptBehavior interruptBehavior() {
                return InterruptBehavior.BLOCK;
            }
        };
    }

    private Tool<Map<String, Object>, String> throwingTool(String name) {
        return new BasicStringTool(name, 4096) {
            @Override
            public ToolResult<String> execute(Map<String, Object> input, ToolUseContext context, ProgressSink progress) {
                throw new IllegalStateException("boom");
            }
        };
    }

    private Tool<Map<String, Object>, Map<String, Object>> stdoutStderrTool(String name, int maxResultSize) {
        return new Tool<>() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public List<String> aliases() {
                return List.of();
            }

            @Override
            public JsonSchema inputSchema() {
                return new JsonSchema(Map.of());
            }

            @Override
            public ValidationResult validateInput(Map<String, Object> input, ToolUseContext context) {
                return new ValidationResult(true, List.of());
            }

            @Override
            public PermissionDecision checkPermissions(Map<String, Object> input, ToolUseContext context) {
                return decision(PermissionBehavior.ALLOW, "allowed");
            }

            @Override
            public ToolResult<Map<String, Object>> execute(
                Map<String, Object> input,
                ToolUseContext context,
                ProgressSink progress
            ) {
                int exitCode = ((Number) input.get("exitCode")).intValue();
                String text = "exitCode=" + exitCode
                    + "\nstdout:\n" + input.get("stdout")
                    + "\nstderr:\n" + input.get("stderr");
                AgentMessage message = message(context.metadata().get("toolUseId").toString(), text, exitCode != 0);
                return new ToolResult<>(
                    Map.of(
                        "exitCode", exitCode,
                        "stdout", input.get("stdout").toString(),
                        "stderr", input.get("stderr").toString()
                    ),
                    exitCode != 0,
                    List.of(message),
                    Optional.empty()
                );
            }

            @Override
            public InterruptBehavior interruptBehavior() {
                return InterruptBehavior.CANCEL;
            }

            @Override
            public boolean isReadOnly(Map<String, Object> input) {
                return true;
            }

            @Override
            public boolean isConcurrencySafe(Map<String, Object> input) {
                return true;
            }

            @Override
            public boolean isDestructive(Map<String, Object> input) {
                return false;
            }

            @Override
            public int maxResultSize() {
                return maxResultSize;
            }

            @Override
            public String renderForUser(Map<String, Object> input) {
                return name + " " + input;
            }

            @Override
            public AgentMessage serializeForContext(Map<String, Object> output) {
                return message("toolu_unknown", output.toString(), false);
            }
        };
    }

    private PermissionDecision decision(PermissionBehavior behavior, String message) {
        return new PermissionDecision(
            behavior,
            PermissionDecisionReason.TOOL_SPECIFIC,
            message,
            Optional.<PermissionUpdate>empty(),
            Map.of()
        );
    }

    private ToolResult<String> result(ToolUseContext context, String text, boolean error) {
        String toolUseId = context.metadata().get("toolUseId").toString();
        return new ToolResult<>(text, error, List.of(message(toolUseId, text, error)), Optional.empty());
    }

    private static AgentMessage message(String toolUseId, String text, boolean error) {
        return new AgentMessage(
            "msg_" + toolUseId,
            MessageRole.TOOL_RESULT,
            MessageKind.TOOL_RESULT,
            List.of(new ToolResultContentBlock(toolUseId, text, error)),
            Instant.EPOCH,
            Optional.empty(),
            Optional.empty()
        );
    }

    private class BasicStringTool implements Tool<Map<String, Object>, String> {
        private final String name;
        private final int maxResultSize;

        BasicStringTool(String name, int maxResultSize) {
            this.name = name;
            this.maxResultSize = maxResultSize;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public List<String> aliases() {
            return List.of();
        }

        @Override
        public JsonSchema inputSchema() {
            return new JsonSchema(Map.of());
        }

        @Override
        public ValidationResult validateInput(Map<String, Object> input, ToolUseContext context) {
            return new ValidationResult(true, List.of());
        }

        @Override
        public PermissionDecision checkPermissions(Map<String, Object> input, ToolUseContext context) {
            return decision(PermissionBehavior.ALLOW, "allowed");
        }

        @Override
        public ToolResult<String> execute(Map<String, Object> input, ToolUseContext context, ProgressSink progress) {
            return result(context, input.getOrDefault("text", "").toString(), false);
        }

        @Override
        public InterruptBehavior interruptBehavior() {
            return InterruptBehavior.CANCEL;
        }

        @Override
        public boolean isReadOnly(Map<String, Object> input) {
            return false;
        }

        @Override
        public boolean isConcurrencySafe(Map<String, Object> input) {
            return false;
        }

        @Override
        public boolean isDestructive(Map<String, Object> input) {
            return false;
        }

        @Override
        public int maxResultSize() {
            return maxResultSize;
        }

        @Override
        public String renderForUser(Map<String, Object> input) {
            return name + " " + input;
        }

        @Override
        public AgentMessage serializeForContext(String output) {
            return message("toolu_unknown", output, false);
        }
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

        private List<ToolEndEvent> toolEnds() {
            return events.stream()
                .filter(ToolEndEvent.class::isInstance)
                .map(ToolEndEvent.class::cast)
                .toList();
        }
    }
}
