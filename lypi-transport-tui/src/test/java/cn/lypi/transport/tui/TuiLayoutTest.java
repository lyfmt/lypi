package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TuiLayoutTest {
    @Test
    void allocatesOnlyTheBoundedSurfaceRowsThatAreNeeded() {
        TuiLayout layout = new TuiLayout(80, 12);

        TuiRegionLayout regions = layout.allocateSurface(1, 3, 2);

        assertEquals(11, layout.maxSurfaceHeight());
        assertEquals(new TuiRegionLayout(1, 3, 2, 1), regions);
        assertEquals(7, regions.totalHeight());
    }

    @Test
    void allocatesAllRegionsWithinTerminalHeight() {
        for (int height : new int[] {2, 3, 6, 24}) {
            for (int desiredInputHeight : new int[] {1, 4, 100}) {
                for (int desiredOverlayHeight : new int[] {0, 3, 100}) {
                    TuiLayout layout = new TuiLayout(80, height);

                    var regions = layout.allocate(desiredInputHeight, desiredOverlayHeight, true);

                    String scenario = "height=" + height
                        + ", input=" + desiredInputHeight
                        + ", overlay=" + desiredOverlayHeight;
                    assertTrue(regions.totalHeight() <= height, scenario);
                    assertEquals(1, regions.statusHeight(), scenario);
                    assertTrue(regions.inputHeight() >= 1, scenario);
                    if (height >= 3) {
                        assertTrue(regions.transcriptHeight() >= 1, scenario);
                    }
                }
            }
        }
    }

    @Test
    void regionLayoutRejectsNegativeHeights() {
        assertThrows(IllegalArgumentException.class, () -> new TuiRegionLayout(-1, 1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new TuiRegionLayout(1, -1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new TuiRegionLayout(1, 1, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> new TuiRegionLayout(1, 1, 0, -1));
    }
}
