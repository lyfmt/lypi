package cn.lypi.agent;

import cn.lypi.agent.compact.DefaultToolMicroCompactor;
import cn.lypi.agent.compact.ToolMicroCompactRequest;
import cn.lypi.agent.compact.ToolMicroCompactResult;
import cn.lypi.agent.compact.ToolMicroCompactor;
import cn.lypi.contracts.common.JsonSchema;
import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContentBlock;
import cn.lypi.contracts.context.ContextBudget;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.TextContentBlock;
import cn.lypi.contracts.context.ToolCallContentBlock;
import cn.lypi.contracts.context.ToolResultContentBlock;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.prompt.SystemPrompt;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.tool.ToolDescriptor;
import cn.lypi.contracts.tool.ToolRegistrySnapshot;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultToolMicroCompactorTest {
    private static final Instant BASE_TIME = Instant.parse("2026-01-01T00:00:00Z");
    private static final ToolRegistrySnapshot TOOLS = new ToolRegistrySnapshot(List.of(
        new ToolDescriptor("read", List.of(), "Read files", new JsonSchema(Map.of("type", "object")), true, false),
        new ToolDescriptor("bash", List.of(), "Run shell", new JsonSchema(Map.of("type", "object")), true, false)
    ));

    @Test
    void coldRequestReplacesOldCompactableToolResults() {
        ToolMicroCompactor compactor = new DefaultToolMicroCompactor(fixedClock(BASE_TIME));
        ContextSnapshot snapshot = snapshot(ThinkingLevel.MEDIUM, historyWithToolRounds(9, "fresh"));

        ToolMicroCompactResult result = compactor.compact(request("session-1", "leaf-1", snapshot));

        assertThat(result.context().messages())
            .filteredOn(message -> message.kind() == MessageKind.TOOL_RESULT)
            .extracting(DefaultToolMicroCompactorTest::toolResultText)
            .contains(DefaultToolMicroCompactor.CLEARED_TOOL_RESULT_TEXT);
        assertThat(result.projectedToolUseIds()).contains("tool-1", "tool-2");
        assertThat(toolResultText(findToolResult(snapshot, "tool-1"))).isEqualTo("result-1");
    }

    @Test
    void hotRequestDoesNotRewriteMessagesWhenCacheEditIsUnavailable() {
        MutableClock clock = new MutableClock(BASE_TIME);
        ToolMicroCompactor compactor = new DefaultToolMicroCompactor(clock);
        ContextSnapshot firstSnapshot = snapshot(ThinkingLevel.MEDIUM, historyWithToolRounds(9, "fresh"));

        compactor.compact(request("session-1", "leaf-1", firstSnapshot));
        clock.advance(Duration.ofMinutes(1));
        ContextSnapshot secondSnapshot = snapshot(ThinkingLevel.MEDIUM, extendedHistory(9, 12, "next turn"));

        ToolMicroCompactResult second = compactor.compact(request("session-1", "leaf-2", secondSnapshot));

        assertThat(second.projectedToolUseIds()).isEmpty();
        assertThat(second.context()).isSameAs(secondSnapshot);
        assertThat(toolResultText(findToolResult(second.context(), "tool-1"))).isEqualTo("result-1");
        assertThat(toolResultText(findToolResult(second.context(), "tool-4"))).isEqualTo("result-4");
    }

    @Test
    void coldAfterFiveMinutesRecalculatesProjectionSet() {
        MutableClock clock = new MutableClock(BASE_TIME);
        ToolMicroCompactor compactor = new DefaultToolMicroCompactor(clock);
        ToolMicroCompactResult first = compactor.compact(request(
            "session-1",
            "leaf-1",
            snapshot(ThinkingLevel.MEDIUM, historyWithToolRounds(9, "fresh"))
        ));
        clock.advance(Duration.ofMinutes(6));
        ContextSnapshot secondSnapshot = snapshot(ThinkingLevel.MEDIUM, extendedHistory(9, 12, "next turn"));

        ToolMicroCompactResult second = compactor.compact(request("session-1", "leaf-2", secondSnapshot));

        assertThat(second.projectedToolUseIds()).contains("tool-3");
        assertThat(second.projectedToolUseIds()).contains(first.projectedToolUseIds().getFirst());
        assertThat(toolResultText(findToolResult(second.context(), "tool-3")))
            .isEqualTo(DefaultToolMicroCompactor.CLEARED_TOOL_RESULT_TEXT);
    }

    @Test
    void resetClearsHotStateSoNextRequestRecalculatesAsCold() {
        MutableClock clock = new MutableClock(BASE_TIME);
        ToolMicroCompactor compactor = new DefaultToolMicroCompactor(clock);
        compactor.compact(request("session-1", "leaf-1", snapshot(ThinkingLevel.MEDIUM, historyWithToolRounds(9, "fresh"))));
        compactor.reset();
        clock.advance(Duration.ofMinutes(1));

        ToolMicroCompactResult result = compactor.compact(request(
            "session-1",
            "leaf-2",
            snapshot(ThinkingLevel.MEDIUM, extendedHistory(9, 12, "next turn"))
        ));

        assertThat(result.projectedToolUseIds()).contains("tool-3");
        assertThat(toolResultText(findToolResult(result.context(), "tool-3")))
            .isEqualTo(DefaultToolMicroCompactor.CLEARED_TOOL_RESULT_TEXT);
    }

    @Test
    void thinkingChangeRecalculatesAsCold() {
        MutableClock clock = new MutableClock(BASE_TIME);
        ToolMicroCompactor compactor = new DefaultToolMicroCompactor(clock);
        compactor.compact(request("session-1", "leaf-1", snapshot(ThinkingLevel.MEDIUM, historyWithToolRounds(9, "fresh"))));
        clock.advance(Duration.ofMinutes(1));

        ToolMicroCompactResult result = compactor.compact(request(
            "session-1",
            "leaf-2",
            snapshot(ThinkingLevel.HIGH, extendedHistory(9, 12, "next turn"))
        ));

        assertThat(result.projectedToolUseIds()).contains("tool-3");
    }

    @Test
    void modelProviderSystemPromptAndToolSchemaChangesRecalculateAsCold() {
        assertColdWhenSecondRequestChanges(snapshot -> snapshotWithModel(snapshot, "other-provider", "test-model"));
        assertColdWhenSecondRequestChanges(snapshot -> snapshotWithModel(snapshot, "test-provider", "other-model"));
        assertColdWhenSecondRequestChanges(snapshot -> snapshotWithSystemPromptHash(snapshot, "other-system-hash"));

        MutableClock clock = new MutableClock(BASE_TIME);
        ToolMicroCompactor compactor = new DefaultToolMicroCompactor(clock);
        compactor.compact(request("session-1", "leaf-1", snapshot(ThinkingLevel.MEDIUM, historyWithToolRounds(9, "fresh"))));
        clock.advance(Duration.ofMinutes(1));

        ToolMicroCompactResult result = compactor.compact(new ToolMicroCompactRequest(
            "session-1",
            Optional.of("leaf-2"),
            snapshot(ThinkingLevel.MEDIUM, extendedHistory(9, 12, "next turn")),
            new ToolRegistrySnapshot(List.of(
                new ToolDescriptor("read", List.of("cat"), "Read files", new JsonSchema(Map.of("type", "object")), true, false),
                new ToolDescriptor("bash", List.of(), "Run shell", new JsonSchema(Map.of("type", "object")), true, false)
            ))
        ));

        assertThat(result.projectedToolUseIds()).contains("tool-3");
    }

    @Test
    void toolInputSchemaChangeRecalculatesAsCold() {
        MutableClock clock = new MutableClock(BASE_TIME);
        ToolMicroCompactor compactor = new DefaultToolMicroCompactor(clock);
        compactor.compact(request("session-1", "leaf-1", snapshot(ThinkingLevel.MEDIUM, historyWithToolRounds(9, "fresh"))));
        clock.advance(Duration.ofMinutes(1));

        ToolMicroCompactResult result = compactor.compact(new ToolMicroCompactRequest(
            "session-1",
            Optional.of("leaf-2"),
            snapshot(ThinkingLevel.MEDIUM, extendedHistory(9, 12, "next turn")),
            new ToolRegistrySnapshot(List.of(
                new ToolDescriptor(
                    "read",
                    List.of(),
                    "Read files",
                    new JsonSchema(Map.of("type", "object", "required", List.of("path"))),
                    true,
                    false
                ),
                new ToolDescriptor("bash", List.of(), "Run shell", new JsonSchema(Map.of("type", "object")), true, false)
            ))
        ));

        assertThat(result.projectedToolUseIds()).contains("tool-3");
    }

    @Test
    void nonlinearBranchRecalculatesAsCold() {
        MutableClock clock = new MutableClock(BASE_TIME);
        ToolMicroCompactor compactor = new DefaultToolMicroCompactor(clock);
        compactor.compact(request("session-1", "leaf-1", snapshot(ThinkingLevel.MEDIUM, historyWithToolRounds(9, "fresh"))));
        clock.advance(Duration.ofMinutes(1));

        List<AgentMessage> forkedHistory = new ArrayList<>(extendedHistory(9, 12, "next turn"));
        forkedHistory.set(0, user("user-fork-root", "fork root"));

        ToolMicroCompactResult result = compactor.compact(request(
            "session-1",
            "fork-leaf",
            snapshot(ThinkingLevel.MEDIUM, forkedHistory)
        ));

        assertThat(result.projectedToolUseIds()).contains("tool-3");
    }

    @Test
    void changedBranchEntryFingerprintRecalculatesAsColdEvenWhenMessagesMatch() {
        MutableClock clock = new MutableClock(BASE_TIME);
        ToolMicroCompactor compactor = new DefaultToolMicroCompactor(clock);
        ContextSnapshot firstSnapshot = snapshot(ThinkingLevel.MEDIUM, historyWithToolRounds(9, "fresh"));
        compactor.compact(new ToolMicroCompactRequest(
            "session-1",
            Optional.of("leaf-1"),
            entryIds(1, firstSnapshot.messages().size()),
            firstSnapshot,
            TOOLS
        ));
        clock.advance(Duration.ofMinutes(1));
        ContextSnapshot secondSnapshot = snapshot(ThinkingLevel.MEDIUM, extendedHistory(9, 12, "next turn"));

        ToolMicroCompactResult result = compactor.compact(new ToolMicroCompactRequest(
            "session-1",
            Optional.of("leaf-2"),
            prefixedEntryIds("fork-entry-", secondSnapshot.messages().size()),
            secondSnapshot,
            TOOLS
        ));

        assertThat(result.projectedToolUseIds()).contains("tool-3");
    }

    @Test
    void currentTurnRecentResultsWritesAndErrorsAreNotReplaced() {
        ToolMicroCompactor compactor = new DefaultToolMicroCompactor(fixedClock(BASE_TIME));
        List<AgentMessage> messages = new ArrayList<>(historyWithToolRounds(8, "fresh"));
        messages.add(toolCall("call-write", "write", "tool-write"));
        messages.add(toolResult("result-write", "tool-write", "write-result", false));
        messages.add(toolCall("call-error", "read", "tool-error"));
        messages.add(toolResult("result-error", "tool-error", "error-result", true));
        messages.add(user("user-current", "current turn"));
        messages.add(toolCall("call-current", "read", "tool-current"));
        messages.add(toolResult("result-current", "tool-current", "current-result", false));

        ToolMicroCompactResult result = compactor.compact(request(
            "session-1",
            "leaf-1",
            snapshot(ThinkingLevel.MEDIUM, messages)
        ));

        assertThat(toolResultText(findToolResult(result.context(), "tool-write"))).isEqualTo("write-result");
        assertThat(toolResultText(findToolResult(result.context(), "tool-error"))).isEqualTo("error-result");
        assertThat(toolResultText(findToolResult(result.context(), "tool-current"))).isEqualTo("current-result");
        assertThat(toolResultText(findToolResult(result.context(), "tool-8"))).isEqualTo("result-8");
    }

    private static ToolMicroCompactRequest request(String sessionId, String leafEntryId, ContextSnapshot snapshot) {
        return new ToolMicroCompactRequest(sessionId, Optional.of(leafEntryId), snapshot, TOOLS);
    }

    private static List<String> entryIds(int startInclusive, int count) {
        return prefixedEntryIds("entry-", startInclusive, count);
    }

    private static List<String> prefixedEntryIds(String prefix, int count) {
        return prefixedEntryIds(prefix, 1, count);
    }

    private static List<String> prefixedEntryIds(String prefix, int startInclusive, int count) {
        List<String> entryIds = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            entryIds.add(prefix + (startInclusive + index));
        }
        return entryIds;
    }

    private static ContextSnapshot snapshot(ThinkingLevel thinkingLevel, List<AgentMessage> messages) {
        return new ContextSnapshot(
            new SystemPrompt("system", List.of("test"), "system-hash"),
            List.copyOf(messages),
            new ModelSelection("test-provider", "test-model", thinkingLevel),
            thinkingLevel,
            AgentMode.EXECUTE,
            PermissionMode.ASK,
            new ContextBudget(0, 128_000, 100_000, 8_192, 16_384, 0, 0, BigDecimal.ZERO)
        );
    }

    private static void assertColdWhenSecondRequestChanges(
        java.util.function.UnaryOperator<ContextSnapshot> secondSnapshotMutation
    ) {
        MutableClock clock = new MutableClock(BASE_TIME);
        ToolMicroCompactor compactor = new DefaultToolMicroCompactor(clock);
        compactor.compact(request("session-1", "leaf-1", snapshot(ThinkingLevel.MEDIUM, historyWithToolRounds(9, "fresh"))));
        clock.advance(Duration.ofMinutes(1));

        ContextSnapshot secondSnapshot = secondSnapshotMutation.apply(snapshot(
            ThinkingLevel.MEDIUM,
            extendedHistory(9, 12, "next turn")
        ));

        ToolMicroCompactResult result = compactor.compact(request("session-1", "leaf-2", secondSnapshot));

        assertThat(result.projectedToolUseIds()).contains("tool-3");
    }

    private static ContextSnapshot snapshotWithModel(ContextSnapshot snapshot, String provider, String modelId) {
        return new ContextSnapshot(
            snapshot.systemPrompt(),
            snapshot.messages(),
            new ModelSelection(provider, modelId, snapshot.thinkingLevel()),
            snapshot.thinkingLevel(),
            snapshot.mode(),
            snapshot.permissionMode(),
            snapshot.budget()
        );
    }

    private static ContextSnapshot snapshotWithSystemPromptHash(ContextSnapshot snapshot, String systemPromptHash) {
        return new ContextSnapshot(
            new SystemPrompt(snapshot.systemPrompt().content(), snapshot.systemPrompt().sourceNames(), systemPromptHash),
            snapshot.messages(),
            snapshot.model(),
            snapshot.thinkingLevel(),
            snapshot.mode(),
            snapshot.permissionMode(),
            snapshot.budget()
        );
    }

    private static List<AgentMessage> historyWithToolRounds(int count, String currentUserText) {
        List<AgentMessage> messages = new ArrayList<>();
        messages.add(user("user-old", "old turn"));
        for (int index = 1; index <= count; index++) {
            messages.add(toolCall("call-" + index, "read", "tool-" + index));
            messages.add(toolResult("result-" + index, "tool-" + index, "result-" + index, false));
        }
        messages.add(user("user-fresh", currentUserText));
        return messages;
    }

    private static List<AgentMessage> extendedHistory(int previousCount, int totalCount, String currentUserText) {
        List<AgentMessage> messages = historyWithToolRounds(previousCount, "fresh");
        for (int index = previousCount + 1; index <= totalCount; index++) {
            messages.add(toolCall("call-" + index, "read", "tool-" + index));
            messages.add(toolResult("result-" + index, "tool-" + index, "result-" + index, false));
        }
        messages.add(user("user-next", currentUserText));
        return messages;
    }

    private static AgentMessage user(String id, String text) {
        return new AgentMessage(
            id,
            MessageRole.USER,
            MessageKind.TEXT,
            List.of(new TextContentBlock(text)),
            BASE_TIME,
            Optional.empty(),
            Optional.empty()
        );
    }

    private static AgentMessage toolCall(String id, String toolName, String toolUseId) {
        return new AgentMessage(
            id,
            MessageRole.ASSISTANT,
            MessageKind.TOOL_CALL,
            List.of(new ToolCallContentBlock(toolUseId, toolName, toolName + " input")),
            BASE_TIME,
            Optional.empty(),
            Optional.empty()
        );
    }

    private static AgentMessage toolResult(String id, String toolUseId, String text, boolean error) {
        return new AgentMessage(
            id,
            MessageRole.TOOL_RESULT,
            MessageKind.TOOL_RESULT,
            List.of(new ToolResultContentBlock(toolUseId, text, error)),
            BASE_TIME,
            Optional.empty(),
            Optional.empty()
        );
    }

    private static AgentMessage findToolResult(ContextSnapshot snapshot, String toolUseId) {
        return snapshot.messages().stream()
            .filter(message -> message.kind() == MessageKind.TOOL_RESULT)
            .filter(message -> ((ToolResultContentBlock) message.content().getFirst()).toolUseId().equals(toolUseId))
            .findFirst()
            .orElseThrow();
    }

    private static String toolResultText(AgentMessage message) {
        ContentBlock block = message.content().getFirst();
        return block.text();
    }

    private static Clock fixedClock(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
