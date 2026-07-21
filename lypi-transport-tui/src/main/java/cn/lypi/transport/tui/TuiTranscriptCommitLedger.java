package cn.lypi.transport.tui;

import cn.lypi.contracts.tui.TuiBlock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

final class TuiTranscriptCommitLedger {
    private TuiProjectionKey projectionKey;
    private final Set<String> committedBlockIds = new LinkedHashSet<>();

    List<TuiBlock> advance(TuiProjectionKey nextKey, List<TuiBlock> stablePrefix) {
        Objects.requireNonNull(nextKey, "nextKey");
        List<TuiBlock> stable = List.copyOf(stablePrefix);
        if (!nextKey.equals(projectionKey)) {
            projectionKey = nextKey;
            committedBlockIds.clear();
        }
        List<TuiBlock> newlyCommitted = new ArrayList<>();
        for (TuiBlock block : stable) {
            if (committedBlockIds.add(block.blockId())) {
                newlyCommitted.add(block);
            }
        }
        return List.copyOf(newlyCommitted);
    }

    void reset() {
        projectionKey = null;
        committedBlockIds.clear();
    }
}

record TuiProjectionKey(String sessionId, String leafId) {
    TuiProjectionKey {
        sessionId = sessionId == null ? "" : sessionId;
        leafId = leafId == null ? "" : leafId;
    }
}
