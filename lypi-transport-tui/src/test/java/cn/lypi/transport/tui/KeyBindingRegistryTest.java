package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

class KeyBindingRegistryTest {
    @Test
    void defaultBindingsCoverPiStyleEditingAndThinkingShortcuts() {
        KeyBindingRegistry registry = KeyBindingRegistry.defaults();

        assertEquals(TerminalInputAction.DELETE_PREVIOUS_WORD, registry.actionFor(TerminalKey.ALT_BACKSPACE));
        assertEquals(TerminalInputAction.DELETE_NEXT_WORD, registry.actionFor(TerminalKey.ALT_DELETE));
        assertEquals(TerminalInputAction.TOGGLE_TOOL_OUTPUT_EXPANDED, registry.actionFor(TerminalKey.CTRL_O));
        assertEquals(TerminalInputAction.INSERT_NEWLINE, registry.actionFor(TerminalKey.MODIFIED_ENTER));
        assertEquals(TerminalInputAction.SCROLL_TRANSCRIPT_UP, registry.actionFor(TerminalKey.PAGE_UP));
        assertEquals(TerminalInputAction.SCROLL_TRANSCRIPT_DOWN, registry.actionFor(TerminalKey.PAGE_DOWN));
    }

    @Test
    void aliasesCanOverrideBindings() {
        KeyBindingRegistry registry = KeyBindingRegistry.fromAliases(Map.of(
            "alt+backspace", "deletePreviousWord",
            "alt+delete", "deleteNextWord",
            "toggleThinking", "toggleThinking",
            "expandTools", "expandTools"
        ));

        assertEquals(TerminalInputAction.DELETE_PREVIOUS_WORD, registry.actionFor(TerminalKey.ALT_BACKSPACE));
        assertEquals(TerminalInputAction.DELETE_NEXT_WORD, registry.actionFor(TerminalKey.ALT_DELETE));
        assertEquals(TerminalInputAction.TOGGLE_THINKING, registry.actionFor(TerminalKey.CTRL_O));
        assertEquals(TerminalInputAction.EXPAND_TOOLS, registry.actionFor(TerminalKey.EXPAND_TOOLS));
    }
}
