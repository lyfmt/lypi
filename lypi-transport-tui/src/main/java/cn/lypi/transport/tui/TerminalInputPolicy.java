package cn.lypi.transport.tui;

public final class TerminalInputPolicy {
    /**
     * 根据当前终端上下文仲裁按键语义。
     */
    public TerminalInputDecision decide(TerminalKey key, TerminalInputContext context) {
        if (key == TerminalKey.BRACKETED_PASTE) {
            return TerminalInputDecision.action(TerminalInputAction.INSERT_PASTE);
        }
        if (key == TerminalKey.ESC && context.permissionOverlayOpen()) {
            return TerminalInputDecision.action(TerminalInputAction.INTERRUPT);
        }
        if (key == TerminalKey.ESC && context.toolRunning()) {
            return TerminalInputDecision.action(TerminalInputAction.INTERRUPT);
        }
        if (key == TerminalKey.CTRL_C) {
            return decideCtrlC(context);
        }
        return TerminalInputDecision.action(TerminalInputAction.NOOP);
    }

    /**
     * 关闭 overlay 后恢复打开前焦点。
     */
    public TerminalInputDecision closeOverlay(TerminalInputContext context) {
        return TerminalInputDecision.focus(context.previousFocusArea());
    }

    private TerminalInputDecision decideCtrlC(TerminalInputContext context) {
        if (!context.input().isBlank()) {
            return TerminalInputDecision.action(TerminalInputAction.CLEAR_INPUT);
        }
        if (context.permissionOverlayOpen()) {
            return TerminalInputDecision.action(TerminalInputAction.INTERRUPT);
        }
        if (context.toolRunning()) {
            return TerminalInputDecision.action(TerminalInputAction.INTERRUPT);
        }
        return TerminalInputDecision.action(TerminalInputAction.EXIT);
    }
}
