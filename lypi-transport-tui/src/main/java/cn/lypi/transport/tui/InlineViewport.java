package cn.lypi.transport.tui;

record InlineViewport(int top, int surfaceHeight, int width, int terminalHeight) {
    InlineViewport {
        if (top < 0 || surfaceHeight < 1 || width < 1 || terminalHeight < 1) {
            throw new IllegalArgumentException("inline viewport dimensions must be positive");
        }
        if (top > terminalHeight - surfaceHeight) {
            throw new IllegalArgumentException("inline viewport must fit within terminal height");
        }
    }

    static InlineViewport at(TerminalPosition position, int width, int terminalHeight) {
        if (width < 1 || terminalHeight < 1) {
            throw new IllegalArgumentException("terminal dimensions must be positive");
        }
        int top = Math.min(position.row(), terminalHeight - 1);
        int initialHeight = Math.max(1, terminalHeight - top - 1);
        return new InlineViewport(top, initialHeight, width, terminalHeight);
    }

    InlineViewport withSurfaceHeight(int nextSurfaceHeight) {
        if (nextSurfaceHeight < 1 || nextSurfaceHeight > terminalHeight) {
            throw new IllegalArgumentException("surface height must fit within terminal height");
        }
        int nextTop = Math.min(top, terminalHeight - nextSurfaceHeight);
        return new InlineViewport(nextTop, nextSurfaceHeight, width, terminalHeight);
    }

    InlineViewport resize(int nextWidth, int nextTerminalHeight) {
        if (nextWidth < 1 || nextTerminalHeight < 1) {
            throw new IllegalArgumentException("terminal dimensions must be positive");
        }
        int nextSurfaceHeight = Math.min(surfaceHeight, nextTerminalHeight);
        int highestVisibleTop = nextTerminalHeight - nextSurfaceHeight;
        boolean bottomAligned = top + surfaceHeight == terminalHeight;
        int nextTop = bottomAligned ? highestVisibleTop : Math.min(top, highestVisibleTop);
        return new InlineViewport(nextTop, nextSurfaceHeight, nextWidth, nextTerminalHeight);
    }
}
