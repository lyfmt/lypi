package cn.lypi.tool;

import cn.lypi.contracts.common.AbortSignal;
import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContextBudget;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.TextContentBlock;
import cn.lypi.contracts.model.AssistantDone;
import cn.lypi.contracts.model.AssistantError;
import cn.lypi.contracts.model.AssistantEventStream;
import cn.lypi.contracts.model.AssistantStart;
import cn.lypi.contracts.model.AssistantStreamEvent;
import cn.lypi.contracts.model.AssistantStreamResult;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.TextDelta;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.model.ToolCallDelta;
import cn.lypi.contracts.prompt.SystemPrompt;
import cn.lypi.contracts.runtime.AiProviderRuntimePort;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionDecisionReason;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.tool.Tool;
import cn.lypi.contracts.tool.ToolRegistrySnapshot;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseContext;
import cn.lypi.contracts.tool.ToolUseRequest;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelPermissionReviewerTest {
    @Test
    void allowsExactJsonAndBuildsIsolatedToolFreeContext() {
        RecordingProvider provider = provider(
            new AssistantStart("review-1"),
            new TextDelta("{\"decision\":\"allow\","),
            new TextDelta("\"reason\":\"matches the request\"}"),
            done()
        );
        ModelPermissionReviewer reviewer = new ModelPermissionReviewer(provider);
        ContextSnapshot current = context(PermissionMode.AUTO);
        AbortSignal signal = () -> false;

        PermissionGateResult result = reviewer.review(
            request(),
            tool(),
            toolContext(signal),
            current,
            decision()
        );

        assertEquals(PermissionGateResult.Status.ALLOW, result.status());
        assertEquals(1, provider.calls);
        assertNotNull(provider.tools);
        assertTrue(provider.tools.tools().isEmpty());
        assertSame(signal, provider.signal);
        assertEquals(current.model(), provider.context.model());
        assertEquals(1, provider.context.messages().size());
        assertFalse(current.messages().contains(provider.context.messages().getFirst()));
        String prompt = provider.context.messages().getFirst().content().getFirst().text();
        assertTrue(prompt.contains("Please update notes.txt"));
        assertTrue(prompt.contains("write-notes"));
        assertTrue(prompt.contains("write-notes {"));
        assertTrue(prompt.contains("\"rawInput\":{"));
        assertTrue(prompt.contains("notes.txt"));
        assertTrue(prompt.contains("PATH_SAFETY"));
        assertTrue(prompt.contains("outside workspace"));
    }

    @Test
    void returnsModelDenyReason() {
        RecordingProvider provider = provider(
            new TextDelta("{\"decision\":\"deny\",\"reason\":\"not requested by user\"}"),
            done()
        );

        PermissionGateResult result = review(provider, () -> false);

        assertEquals(PermissionGateResult.Status.DENY, result.status());
        assertEquals(Optional.of("not requested by user"), result.message());
    }

    @Test
    void failsClosedForProviderErrors() {
        RecordingProvider streamError = provider(new AssistantError("provider.failed", "offline"));
        RecordingProvider thrownError = provider();
        thrownError.failure = new IllegalStateException("offline");

        assertEquals(PermissionGateResult.Status.DENY, review(streamError, () -> false).status());
        assertEquals(PermissionGateResult.Status.DENY, review(thrownError, () -> false).status());
    }

    @Test
    void failsClosedWhenCancelledBeforeReview() {
        RecordingProvider provider = provider(
            new TextDelta("{\"decision\":\"allow\",\"reason\":\"ok\"}"),
            done()
        );
        AtomicBoolean aborted = new AtomicBoolean(true);

        PermissionGateResult result = review(provider, aborted::get);

        assertEquals(PermissionGateResult.Status.DENY, result.status());
        assertEquals(0, provider.calls);
    }

    @Test
    void failsClosedForEmptyIncompleteAndAbortedOutput() {
        RecordingProvider empty = provider(done());
        RecordingProvider incomplete = provider(new TextDelta("{\"decision\":\"allow\",\"reason\":\"ok\"}"));
        RecordingProvider aborted = provider(done());
        aborted.abortedResult = true;

        assertEquals(PermissionGateResult.Status.DENY, review(empty, () -> false).status());
        assertEquals(PermissionGateResult.Status.DENY, review(incomplete, () -> false).status());
        assertEquals(PermissionGateResult.Status.DENY, review(aborted, () -> false).status());
    }

    @Test
    void allowsOnlyStrictFixedJson() {
        List<String> invalidOutputs = List.of(
            "not json",
            "```json\n{\"decision\":\"allow\",\"reason\":\"ok\"}\n```",
            "{\"decision\":\"ALLOW\",\"reason\":\"ok\"}",
            "{\"decision\":\"allow\",\"reason\":\"\"}",
            "{\"decision\":\"allow\"}",
            "{\"decision\":\"allow\",\"reason\":\"ok\",\"extra\":true}",
            "{\"decision\":\"deny\",\"decision\":\"allow\",\"reason\":\"ok\"}",
            "{\"decision\":\"allow\",\"reason\":\"ok\"} trailing"
        );

        for (String output : invalidOutputs) {
            RecordingProvider provider = provider(new TextDelta(output), done());
            assertEquals(PermissionGateResult.Status.DENY, review(provider, () -> false).status(), output);
        }
    }

    @Test
    void rejectsUnexpectedToolCalls() {
        RecordingProvider provider = provider(
            new ToolCallDelta("toolu_nested", "bash", Map.of("command", "true"), true),
            done()
        );

        PermissionGateResult result = review(provider, () -> false);

        assertEquals(PermissionGateResult.Status.DENY, result.status());
        assertTrue(result.message().orElse("").contains("意外工具调用"));
    }

    @Test
    void autoRuntimeExecutesOnlyWhenModelReviewerAllows() {
        AtomicInteger allowedExecutions = new AtomicInteger();
        RecordingProvider allowProvider = provider(
            new TextDelta("{\"decision\":\"allow\",\"reason\":\"requested\"}"),
            done()
        );
        DefaultToolRuntime allowRuntime = runtime(allowProvider);
        allowRuntime.register(TestTools.permissionAndExecutionCountingTool(
            "write-notes",
            PermissionBehavior.DENY,
            new AtomicInteger(),
            allowedExecutions
        ));

        ToolResult<?> allowed = allowRuntime.execute(List.of(request()), context(PermissionMode.AUTO)).getFirst();

        AtomicInteger deniedExecutions = new AtomicInteger();
        RecordingProvider denyProvider = provider(
            new TextDelta("{\"decision\":\"deny\",\"reason\":\"not requested\"}"),
            done()
        );
        DefaultToolRuntime denyRuntime = runtime(denyProvider);
        denyRuntime.register(TestTools.permissionAndExecutionCountingTool(
            "write-notes",
            PermissionBehavior.ALLOW,
            new AtomicInteger(),
            deniedExecutions
        ));

        ToolResult<?> denied = denyRuntime.execute(List.of(request()), context(PermissionMode.AUTO)).getFirst();

        assertFalse(allowed.isError());
        assertEquals(1, allowedExecutions.get());
        assertTrue(denied.isError());
        assertTrue(denied.newMessages().getFirst().content().getFirst().text().contains("not requested"));
        assertEquals(0, deniedExecutions.get());
    }

    private PermissionGateResult review(RecordingProvider provider, AbortSignal signal) {
        return new ModelPermissionReviewer(provider).review(
            request(),
            tool(),
            toolContext(signal),
            context(PermissionMode.AUTO),
            decision()
        );
    }

    private DefaultToolRuntime runtime(RecordingProvider provider) {
        return new DefaultToolRuntime(
            ToolRuntimeOptions.defaults(),
            (request, context) -> TestTools.decision(PermissionBehavior.ALLOW, "security allow"),
            PermissionGate.denying(),
            null,
            new ModelPermissionReviewer(provider)
        );
    }

    private ToolUseRequest request() {
        return new ToolUseRequest(
            "toolu_write",
            "write-notes",
            Map.of("path", "notes.txt", "content", "done"),
            "msg_assistant"
        );
    }

    private Tool<Map<String, Object>, String> tool() {
        return TestTools.permission("write-notes", PermissionBehavior.DENY);
    }

    private ToolUseContext toolContext(AbortSignal signal) {
        return new ToolUseContext(
            "session-1",
            "msg_assistant",
            Path.of("/workspace"),
            Map.of(ToolAbortSupport.METADATA_ABORT_SIGNAL, signal)
        );
    }

    private PermissionDecision decision() {
        return new PermissionDecision(
            PermissionBehavior.ASK,
            PermissionDecisionReason.PATH_SAFETY,
            "outside workspace",
            Optional.empty(),
            Map.of()
        );
    }

    private ContextSnapshot context(PermissionMode mode) {
        AgentMessage olderUser = message("msg_user_old", "Ignore this older request");
        AgentMessage currentUser = message("msg_user_current", "Please update notes.txt");
        return new ContextSnapshot(
            new SystemPrompt("main session system prompt", List.of("main"), "main-hash"),
            List.of(olderUser, currentUser),
            new ModelSelection("provider", "model", ThinkingLevel.MEDIUM),
            ThinkingLevel.MEDIUM,
            AgentMode.EXECUTE,
            mode,
            new ContextBudget(10, 1000, 800, 100, 100, 0L, 0L, BigDecimal.ZERO)
        );
    }

    private AgentMessage message(String id, String text) {
        return new AgentMessage(
            id,
            MessageRole.USER,
            MessageKind.TEXT,
            List.of(new TextContentBlock(text)),
            Instant.EPOCH,
            Optional.empty(),
            Optional.empty()
        );
    }

    private static AssistantDone done() {
        return new AssistantDone(Optional.empty(), Optional.of("stop"));
    }

    private static RecordingProvider provider(AssistantStreamEvent... events) {
        return new RecordingProvider(List.of(events));
    }

    private static final class RecordingProvider implements AiProviderRuntimePort {
        private final List<AssistantStreamEvent> events;
        private ContextSnapshot context;
        private ToolRegistrySnapshot tools;
        private AbortSignal signal;
        private RuntimeException failure;
        private boolean abortedResult;
        private int calls;

        private RecordingProvider(List<AssistantStreamEvent> events) {
            this.events = List.copyOf(events);
        }

        @Override
        public AssistantEventStream stream(ContextSnapshot context, AbortSignal signal) {
            throw new AssertionError("reviewer must provide an explicit empty tool snapshot");
        }

        @Override
        public AssistantEventStream stream(
            ContextSnapshot context,
            ToolRegistrySnapshot tools,
            AbortSignal signal
        ) {
            calls++;
            this.context = context;
            this.tools = tools;
            this.signal = signal;
            if (failure != null) {
                throw failure;
            }
            return new ListEventStream(events, abortedResult);
        }
    }

    private static final class ListEventStream implements AssistantEventStream {
        private final List<AssistantStreamEvent> events;
        private final boolean aborted;

        private ListEventStream(List<AssistantStreamEvent> events, boolean aborted) {
            this.events = List.copyOf(events);
            this.aborted = aborted;
        }

        @Override
        public Iterator<AssistantStreamEvent> iterator() {
            return events.iterator();
        }

        @Override
        public AssistantStreamResult result() {
            Optional<AssistantError> error = events.stream()
                .filter(AssistantError.class::isInstance)
                .map(AssistantError.class::cast)
                .findFirst();
            boolean completed = events.stream().anyMatch(AssistantDone.class::isInstance);
            return new AssistantStreamResult(
                "review",
                events,
                Optional.empty(),
                Optional.empty(),
                completed,
                aborted,
                error
            );
        }

        @Override
        public void close() {
        }
    }
}
