package cn.lypi.runtime.memory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.TextContentBlock;
import cn.lypi.contracts.context.ToolCallContentBlock;
import cn.lypi.contracts.event.TurnEndEvent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MemoryConsolidationTriggerTest {
    private static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");

    private final MemoryConsolidationTrigger trigger = new MemoryConsolidationTrigger(10, 5, 2);

    @Test
    void skipsNonCompletedTurns() {
        assertFalse(trigger.isEligible(event("FAILED"), false));
        assertFalse(trigger.isEligible(event("ABORTED"), false));
    }

    @Test
    void skipsConsolidationSessionToPreventRecursion() {
        assertFalse(trigger.isEligible(event("COMPLETED"), true));
    }

    @Test
    void initializesOnlyAfterContextTokenThreshold() {
        MemoryConsolidationTrigger.ExtractionState state = new MemoryConsolidationTrigger.ExtractionState();

        assertFalse(trigger.shouldExtract(List.of(text("msg-1", "short")), state));
        assertTrue(trigger.shouldExtract(List.of(text("msg-1", "0123456789012345678901234567890123456789")), state));
    }

    @Test
    void requiresTokenGrowthEvenWhenToolCallThresholdIsMet() {
        MemoryConsolidationTrigger.ExtractionState state = new MemoryConsolidationTrigger.ExtractionState();
        assertTrue(trigger.shouldExtract(List.of(text("msg-1", "0123456789012345678901234567890123456789")), state));

        assertFalse(trigger.shouldExtract(List.of(
            text("msg-1", "0123456789012345678901234567890123456789"),
            tool("msg-2"),
            tool("msg-3")
        ), state));
    }

    @Test
    void triggersWhenTokenGrowthAndToolCallThresholdAreMet() {
        MemoryConsolidationTrigger.ExtractionState state = new MemoryConsolidationTrigger.ExtractionState();
        assertTrue(trigger.shouldExtract(List.of(text("msg-1", "0123456789012345678901234567890123456789")), state));

        assertTrue(trigger.shouldExtract(List.of(
            text("msg-1", "0123456789012345678901234567890123456789"),
            text("msg-2", "012345678901234567890123456789"),
            tool("msg-3"),
            tool("msg-4")
        ), state));
    }

    @Test
    void triggersAtNaturalBreakWhenTokenGrowthIsMet() {
        MemoryConsolidationTrigger.ExtractionState state = new MemoryConsolidationTrigger.ExtractionState();
        assertTrue(trigger.shouldExtract(List.of(text("msg-1", "0123456789012345678901234567890123456789")), state));

        assertTrue(trigger.shouldExtract(List.of(
            text("msg-1", "0123456789012345678901234567890123456789"),
            text("msg-2", "012345678901234567890123456789")
        ), state));
    }

    @Test
    void defaultThresholdsMatchClaudeCodeSessionMemoryDefaults() {
        MemoryConsolidationTrigger defaults = new MemoryConsolidationTrigger();

        assertFalse(defaults.shouldExtract(List.of(text("msg-1", "x".repeat(39_996))), new MemoryConsolidationTrigger.ExtractionState()));
        assertTrue(defaults.shouldExtract(List.of(text("msg-1", "x".repeat(40_000))), new MemoryConsolidationTrigger.ExtractionState()));
    }

    private TurnEndEvent event(String status) {
        return new TurnEndEvent(
            "ses_1",
            "turn_1",
            status,
            NOW,
            NOW.plusMillis(1_000L),
            1_000L,
            0,
            NOW.plusMillis(1_000L)
        );
    }

    private static AgentMessage text(String id, String text) {
        return new AgentMessage(
            id,
            MessageRole.ASSISTANT,
            MessageKind.TEXT,
            List.of(new TextContentBlock(text)),
            NOW,
            Optional.empty(),
            Optional.empty()
        );
    }

    private static AgentMessage tool(String id) {
        return new AgentMessage(
            id,
            MessageRole.ASSISTANT,
            MessageKind.TOOL_CALL,
            List.of(new ToolCallContentBlock("toolu-" + id, "read", "", Map.of("complete", true))),
            NOW,
            Optional.empty(),
            Optional.empty()
        );
    }
}
