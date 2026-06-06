package cn.lypi.contracts;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.lypi.contracts.session.SessionEntry;
import cn.lypi.contracts.tui.TuiEphemeralState;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TuiEphemeralStateBoundaryTest {
    @Test
    void ephemeralStateIsNotASessionEntry() {
        TuiEphemeralState state = new TuiEphemeralState(
            Set.of("block-1"),
            Set.of("tool-1"),
            Optional.of("overlay-files"),
            12,
            Map.of("pane", "diff")
        );

        assertFalse(SessionEntry.class.isAssignableFrom(state.getClass()));
    }

    @Test
    void ephemeralStateCopiesMutableCollections() {
        Set<String> collapsed = new HashSet<>(Set.of("block-1"));
        Map<String, Object> metadata = new HashMap<>(Map.of("pane", "diff"));

        TuiEphemeralState state = new TuiEphemeralState(collapsed, Set.of(), Optional.empty(), 0, metadata);
        collapsed.add("block-2");
        metadata.put("pane", "files");

        assertFalse(state.collapsedBlockIds().contains("block-2"));
        assertThrows(UnsupportedOperationException.class, () -> state.metadata().put("pane", "changed"));
    }
}
