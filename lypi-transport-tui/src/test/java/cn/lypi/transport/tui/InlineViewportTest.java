package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class InlineViewportTest {
    @Test
    void surfaceAndTerminalResizePreserveReachableViewportGeometry() {
        InlineViewport viewport = InlineViewport.at(new TerminalPosition(0, 8), 80, 12);

        assertEquals(new InlineViewport(8, 3, 80, 12), viewport.withSurfaceHeight(3));
        assertEquals(new InlineViewport(6, 6, 80, 12), viewport.withSurfaceHeight(6));
        assertEquals(new InlineViewport(5, 3, 60, 8), viewport.resize(60, 8));
        assertEquals(
            new InlineViewport(9, 3, 80, 12),
            new InlineViewport(5, 3, 80, 8).resize(80, 12)
        );
    }
}
