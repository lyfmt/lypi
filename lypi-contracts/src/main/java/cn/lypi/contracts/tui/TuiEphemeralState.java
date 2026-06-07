package cn.lypi.contracts.tui;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

public record TuiEphemeralState(
    Set<String> collapsedBlockIds,
    Set<String> expandedOutputIds,
    Optional<String> activeOverlayId,
    int scrollOffset,
    Map<String, Object> metadata
) {
    public TuiEphemeralState {
        collapsedBlockIds = collapsedBlockIds == null ? Set.of() : Set.copyOf(collapsedBlockIds);
        expandedOutputIds = expandedOutputIds == null ? Set.of() : Set.copyOf(expandedOutputIds);
        activeOverlayId = activeOverlayId == null ? Optional.empty() : activeOverlayId;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
