package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TuiLayoutTest {
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
}
