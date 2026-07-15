package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TuiContentLayoutTest {
    @Test
    void allocatesTinyBudgetsBetweenHistoryAndLiveRegions() {
        assertLayout(TuiContentLayout.allocate(0, true, 20), 0, 0, 0);
        assertLayout(TuiContentLayout.allocate(1, true, 20), 0, 0, 1);
        assertLayout(TuiContentLayout.allocate(2, true, 20), 1, 0, 1);
        assertLayout(TuiContentLayout.allocate(3, true, 20), 1, 1, 1);
    }

    @Test
    void capsLiveRegionAtHalfOfContentAfterSeparator() {
        TuiContentLayout split = TuiContentLayout.allocate(10, true, 20);

        assertLayout(split, 5, 1, 4);
        assertTrue(split.liveHeight() <= 4);
        assertTrue(split.historyHeight() >= split.liveHeight());
    }

    @Test
    void allocatesWholeBudgetToSingleOrEmptyRegion() {
        assertLayout(TuiContentLayout.allocate(10, true, 0), 10, 0, 0);
        assertLayout(TuiContentLayout.allocate(10, false, 3), 0, 0, 10);
        assertLayout(TuiContentLayout.allocate(10, false, 0), 10, 0, 0);
    }

    @Test
    void usesOnlyDesiredLiveHeightWhenHistoryCanUseTheRemainder() {
        assertLayout(TuiContentLayout.allocate(10, true, 1), 8, 1, 1);
    }

    @Test
    void rejectsNegativeInputs() {
        assertThrows(IllegalArgumentException.class, () -> TuiContentLayout.allocate(-1, true, 1));
        assertThrows(IllegalArgumentException.class, () -> TuiContentLayout.allocate(1, true, -1));
    }

    private void assertLayout(
        TuiContentLayout layout,
        int expectedHistory,
        int expectedSeparator,
        int expectedLive
    ) {
        assertEquals(expectedHistory, layout.historyHeight());
        assertEquals(expectedSeparator, layout.separatorHeight());
        assertEquals(expectedLive, layout.liveHeight());
        assertEquals(expectedHistory + expectedSeparator + expectedLive, layout.totalHeight());
    }
}
