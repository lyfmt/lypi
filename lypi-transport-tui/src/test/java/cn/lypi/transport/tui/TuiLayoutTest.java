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
    void allocatesAllSurfaceRegionsWithinReservedTerminalBudget() {
        for (int height : new int[] {2, 3, 6, 24}) {
            for (int desiredLiveHeight : new int[] {0, 3, 100}) {
            for (int desiredInputHeight : new int[] {1, 4, 100}) {
                for (int desiredOverlayHeight : new int[] {0, 3, 100}) {
                    TuiLayout layout = new TuiLayout(80, height);

                    var regions = layout.allocateSurface(
                        desiredLiveHeight,
                        desiredInputHeight,
                        desiredOverlayHeight
                    );

                    String scenario = "height=" + height
                        + ", live=" + desiredLiveHeight
                        + ", input=" + desiredInputHeight
                        + ", overlay=" + desiredOverlayHeight;
                    assertTrue(regions.totalHeight() <= height - 1, scenario);
                    assertEquals(height > 2 ? 1 : 0, regions.statusHeight(), scenario);
                    assertTrue(regions.inputHeight() >= 1, scenario);
                    assertTrue(regions.transcriptHeight() <= desiredLiveHeight, scenario);
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
