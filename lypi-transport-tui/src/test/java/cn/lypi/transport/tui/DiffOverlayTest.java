package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.lypi.contracts.tui.DiffView;
import java.util.List;
import org.junit.jupiter.api.Test;

class DiffOverlayTest {
    @Test
    void rendersOnlyDiffPathAndReference() {
        DiffOverlay overlay = new DiffOverlay(new DiffView("src/Main.java", "diff_ref_1"));

        assertEquals(List.of("diff: src/Main.java", "ref: diff_ref_1"), overlay.lines());
    }
}
