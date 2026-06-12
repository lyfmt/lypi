package cn.lypi.transport.tui;

final class TuiRenderOptions {
    private boolean toolOutputExpanded;

    boolean toolOutputExpanded() {
        return toolOutputExpanded;
    }

    void toggleToolOutputExpanded() {
        toolOutputExpanded = !toolOutputExpanded;
    }
}
