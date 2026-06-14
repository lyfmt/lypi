package cn.lypi.runtime.memory;

import cn.lypi.contracts.event.TurnEndEvent;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryConsolidationTriggerTest {
    private static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");

    private final MemoryConsolidationTrigger trigger = new MemoryConsolidationTrigger(1_200_000L, 30);

    @Test
    void skipsNonCompletedTurns() {
        assertFalse(trigger.shouldTrigger(event("FAILED", 1_500_000L, 31), false));
        assertFalse(trigger.shouldTrigger(event("ABORTED", 1_500_000L, 31), false));
    }

    @Test
    void triggersAtDurationThreshold() {
        assertTrue(trigger.shouldTrigger(event("COMPLETED", 1_200_000L, 0), false));
    }

    @Test
    void triggersWhenToolRoundsExceedThreshold() {
        assertTrue(trigger.shouldTrigger(event("COMPLETED", 1_000L, 31), false));
    }

    @Test
    void skipsAtToolRoundThresholdWhenDurationIsShort() {
        assertFalse(trigger.shouldTrigger(event("COMPLETED", 1_000L, 30), false));
    }

    @Test
    void skipsConsolidationSessionToPreventRecursion() {
        assertFalse(trigger.shouldTrigger(event("COMPLETED", 1_500_000L, 31), true));
    }

    private TurnEndEvent event(String status, long durationMillis, int toolRounds) {
        return new TurnEndEvent(
            "ses_1",
            "turn_1",
            status,
            NOW,
            NOW.plusMillis(durationMillis),
            durationMillis,
            toolRounds,
            NOW.plusMillis(durationMillis)
        );
    }
}
