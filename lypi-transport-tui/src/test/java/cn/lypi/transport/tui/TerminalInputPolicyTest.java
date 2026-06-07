package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TerminalInputPolicyTest {
    @Test
    void ctrlCClearsNonEmptyInput() {
        TerminalInputPolicy policy = new TerminalInputPolicy();
        TerminalInputContext context = new TerminalInputContext("hello", false, false, "chat", "chat", "cancel");

        TerminalInputDecision decision = policy.decide(TerminalKey.CTRL_C, context);

        assertEquals(TerminalInputAction.CLEAR_INPUT, decision.action());
        assertFalse(decision.optionId().isPresent());
    }

    @Test
    void ctrlCInterruptsRunningToolWhenInputIsEmpty() {
        TerminalInputPolicy policy = new TerminalInputPolicy();
        TerminalInputContext context = new TerminalInputContext("", true, false, "chat", "chat", "cancel");

        TerminalInputDecision decision = policy.decide(TerminalKey.CTRL_C, context);

        assertEquals(TerminalInputAction.INTERRUPT, decision.action());
    }

    @Test
    void escapeAndCtrlCCancelPermissionOverlay() {
        TerminalInputPolicy policy = new TerminalInputPolicy();
        TerminalInputContext context = new TerminalInputContext("", true, true, "permission", "editor", "cancel_once");

        TerminalInputDecision escape = policy.decide(TerminalKey.ESC, context);
        TerminalInputDecision ctrlC = policy.decide(TerminalKey.CTRL_C, context);

        assertEquals(TerminalInputAction.SUBMIT_PERMISSION_OPTION, escape.action());
        assertEquals(TerminalInputAction.SUBMIT_PERMISSION_OPTION, ctrlC.action());
        assertEquals("cancel_once", escape.optionId().orElseThrow());
        assertEquals("cancel_once", ctrlC.optionId().orElseThrow());
    }

    @Test
    void bracketedPasteDoesNotSubmitLines() {
        TerminalInputPolicy policy = new TerminalInputPolicy();
        TerminalInputContext context = new TerminalInputContext("", false, false, "chat", "chat", "cancel");

        TerminalInputDecision decision = policy.decide(TerminalKey.BRACKETED_PASTE, context);

        assertEquals(TerminalInputAction.INSERT_PASTE, decision.action());
    }

    @Test
    void overlayCloseRestoresPreviousFocus() {
        TerminalInputPolicy policy = new TerminalInputPolicy();
        TerminalInputContext context = new TerminalInputContext("", false, true, "permission", "editor", "cancel");

        TerminalInputDecision decision = policy.closeOverlay(context);

        assertEquals(TerminalInputAction.RESTORE_FOCUS, decision.action());
        assertEquals("editor", decision.focusTarget().orElseThrow());
        assertTrue(decision.optionId().isEmpty());
    }
}
