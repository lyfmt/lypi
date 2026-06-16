package cn.lypi.transport.tui;

import cn.lypi.contracts.event.AgentEvent;
import cn.lypi.contracts.event.ToolEndEvent;
import cn.lypi.contracts.tui.DiffView;
import cn.lypi.contracts.tui.DiffViewProvider;
import cn.lypi.contracts.tui.SessionRuntimeState;
import java.time.Instant;

final class TuiRuntimeTicker {
    private final long runtimeTickIntervalMillis;
    private final int maxDiffPatchBytes;
    private Instant nextRuntimeTickAt;
    private String lastDiffSnapshotHash = "";

    TuiRuntimeTicker(long runtimeTickIntervalMillis, int maxDiffPatchBytes) {
        this.runtimeTickIntervalMillis = runtimeTickIntervalMillis;
        this.maxDiffPatchBytes = maxDiffPatchBytes;
    }

    void renderTickIfDue(boolean activeTurn, Instant now, Runnable renderAction) {
        if (!activeTurn) {
            nextRuntimeTickAt = null;
            return;
        }
        if (nextRuntimeTickAt == null) {
            nextRuntimeTickAt = now.plusMillis(runtimeTickIntervalMillis);
            return;
        }
        if (now.isBefore(nextRuntimeTickAt)) {
            return;
        }
        renderAction.run();
        nextRuntimeTickAt = now.plusMillis(runtimeTickIntervalMillis);
    }

    void refreshDiffAfterToolEnd(
        AgentEvent event,
        SessionRuntimeState runtimeState,
        TuiEventReducer reducer,
        DiffViewProvider diffViewProvider
    ) {
        if (!(event instanceof ToolEndEvent) || reducer == null) {
            return;
        }
        if (runtimeState == null || runtimeState.cwd() == null || diffViewProvider == null) {
            clearDiff(reducer);
            return;
        }
        diffViewProvider.currentDiff(runtimeState.cwd(), maxDiffPatchBytes)
            .ifPresentOrElse(diffView -> showDiffIfChanged(diffView, reducer), () -> clearDiff(reducer));
    }

    private void showDiffIfChanged(DiffView diffView, TuiEventReducer reducer) {
        String snapshotHash = snapshotHash(diffView);
        if (snapshotHash.isBlank() || !snapshotHash.equals(lastDiffSnapshotHash)) {
            reducer.showDiff(diffView);
            lastDiffSnapshotHash = snapshotHash;
        }
    }

    private void clearDiff(TuiEventReducer reducer) {
        reducer.clearDiff();
        lastDiffSnapshotHash = "";
    }

    private String snapshotHash(DiffView diffView) {
        Object value = diffView.metadata().get("snapshotHash");
        return value == null ? "" : value.toString();
    }
}
