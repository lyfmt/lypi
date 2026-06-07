package cn.lypi.transport.tui;

public record TerminalInputContext(
    String input,
    boolean toolRunning,
    boolean permissionOverlayOpen,
    String focusArea,
    String previousFocusArea,
    String cancelOptionId
) {
    public TerminalInputContext {
        input = input == null ? "" : input;
        focusArea = focusArea == null ? "" : focusArea;
        previousFocusArea = previousFocusArea == null ? "" : previousFocusArea;
        cancelOptionId = cancelOptionId == null ? "" : cancelOptionId;
    }
}
