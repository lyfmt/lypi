package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.event.ErrorEvent;
import cn.lypi.contracts.event.ToolEndEvent;
import cn.lypi.contracts.context.ContextBudget;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.tui.DiffView;
import cn.lypi.contracts.tui.DiffViewProvider;
import cn.lypi.contracts.tui.GitDiffFileView;
import cn.lypi.contracts.tui.GitDiffStatus;
import cn.lypi.contracts.tui.SessionRuntimeState;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TuiRuntimeTickerTest {
    @Test
    void schedulesFirstTickWithoutRenderingThenRendersWhenDue() {
        TuiRuntimeTicker ticker = new TuiRuntimeTicker(1_000L, 64 * 1024);
        AtomicInteger renders = new AtomicInteger();

        ticker.renderTickIfDue(true, Instant.parse("2026-06-09T00:00:00Z"), renders::incrementAndGet);
        ticker.renderTickIfDue(true, Instant.parse("2026-06-09T00:00:00.999Z"), renders::incrementAndGet);
        ticker.renderTickIfDue(true, Instant.parse("2026-06-09T00:00:01Z"), renders::incrementAndGet);

        assertEquals(1, renders.get());
    }

    @Test
    void clearsScheduleWhenTurnIsInactive() {
        TuiRuntimeTicker ticker = new TuiRuntimeTicker(1_000L, 64 * 1024);
        AtomicInteger renders = new AtomicInteger();

        ticker.renderTickIfDue(true, Instant.parse("2026-06-09T00:00:00Z"), renders::incrementAndGet);
        ticker.renderTickIfDue(false, Instant.parse("2026-06-09T00:00:00.500Z"), renders::incrementAndGet);
        ticker.renderTickIfDue(true, Instant.parse("2026-06-09T00:00:01.500Z"), renders::incrementAndGet);

        assertEquals(0, renders.get());
    }

    @Test
    void refreshesDiffOnlyForToolEndAndSkipsDuplicateSnapshot() {
        TuiRuntimeTicker ticker = new TuiRuntimeTicker(1_000L, 64 * 1024);
        TuiEventReducer reducer = new TuiEventReducer();
        CountingDiffProvider provider = new CountingDiffProvider(Optional.of(diff("sha256:1")));
        SessionRuntimeState state = runtimeState();

        ticker.refreshDiffAfterToolEnd(new ErrorEvent("ses_1", "err", "boom", Instant.EPOCH), state, reducer, provider);
        ticker.refreshDiffAfterToolEnd(new ToolEndEvent("ses_1", "tool_1", false, Instant.EPOCH), state, reducer, provider);
        ticker.refreshDiffAfterToolEnd(new ToolEndEvent("ses_1", "tool_2", false, Instant.EPOCH), state, reducer, provider);

        assertEquals(2, provider.calls);
        assertEquals("diff: 1 file changed", reducer.view().diffView().orElseThrow().summary());
    }

    @Test
    void clearsDiffWhenProviderReturnsEmpty() {
        TuiRuntimeTicker ticker = new TuiRuntimeTicker(1_000L, 64 * 1024);
        TuiEventReducer reducer = new TuiEventReducer();
        SessionRuntimeState state = runtimeState();

        ticker.refreshDiffAfterToolEnd(
            new ToolEndEvent("ses_1", "tool_1", false, Instant.EPOCH),
            state,
            reducer,
            (cwd, maxPatchBytes) -> Optional.of(diff("sha256:1"))
        );
        assertTrue(reducer.view().diffView().isPresent());

        ticker.refreshDiffAfterToolEnd(
            new ToolEndEvent("ses_1", "tool_2", false, Instant.EPOCH),
            state,
            reducer,
            (cwd, maxPatchBytes) -> Optional.empty()
        );

        assertTrue(reducer.view().diffView().isEmpty());
    }

    private static DiffView diff(String snapshotHash) {
        return new DiffView(
            "diff: 1 file changed",
            List.of(new GitDiffFileView(Path.of("README.md"), GitDiffStatus.MODIFIED, "modified", Map.of())),
            "patch",
            false,
            Map.of("snapshotHash", snapshotHash)
        );
    }

    private static SessionRuntimeState runtimeState() {
        return new SessionRuntimeState(
            "ses_1",
            Path.of("."),
            "entry_1",
            new ModelSelection("openai", "gpt-5.4", ThinkingLevel.HIGH),
            ThinkingLevel.HIGH,
            cn.lypi.contracts.security.AgentMode.EXECUTE,
            cn.lypi.contracts.security.PermissionMode.ASK,
            new ContextBudget(0, 0, 0, 0, 0, 0, 0, java.math.BigDecimal.ZERO),
            List.of(),
            false,
            false,
            false,
            false
        );
    }

    private static final class CountingDiffProvider implements DiffViewProvider {
        private final Optional<DiffView> diff;
        private int calls;

        private CountingDiffProvider(Optional<DiffView> diff) {
            this.diff = diff;
        }

        @Override
        public Optional<DiffView> currentDiff(Path cwd, int maxPatchBytes) {
            calls++;
            return diff;
        }
    }
}
