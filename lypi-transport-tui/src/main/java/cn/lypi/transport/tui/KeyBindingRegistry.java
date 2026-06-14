package cn.lypi.transport.tui;

import java.util.EnumMap;
import java.util.Map;

final class KeyBindingRegistry {
    private final Map<TerminalKey, TerminalInputAction> bindings;

    private KeyBindingRegistry(Map<TerminalKey, TerminalInputAction> bindings) {
        this.bindings = new EnumMap<>(bindings);
    }

    static KeyBindingRegistry defaults() {
        Map<TerminalKey, TerminalInputAction> bindings = new EnumMap<>(TerminalKey.class);
        bindings.put(TerminalKey.MODIFIED_ENTER, TerminalInputAction.INSERT_NEWLINE);
        bindings.put(TerminalKey.BACKSPACE, TerminalInputAction.DELETE_PREVIOUS_CHARACTER);
        bindings.put(TerminalKey.ALT_BACKSPACE, TerminalInputAction.DELETE_PREVIOUS_WORD);
        bindings.put(TerminalKey.ALT_DELETE, TerminalInputAction.DELETE_NEXT_WORD);
        bindings.put(TerminalKey.CTRL_U, TerminalInputAction.DELETE_LINE_BEFORE_CURSOR);
        bindings.put(TerminalKey.CTRL_Z, TerminalInputAction.UNDO);
        bindings.put(TerminalKey.CTRL_Y, TerminalInputAction.YANK);
        bindings.put(TerminalKey.ALT_Y, TerminalInputAction.YANK_POP);
        bindings.put(TerminalKey.LEFT, TerminalInputAction.MOVE_LEFT);
        bindings.put(TerminalKey.RIGHT, TerminalInputAction.MOVE_RIGHT);
        bindings.put(TerminalKey.WORD_LEFT, TerminalInputAction.MOVE_WORD_LEFT);
        bindings.put(TerminalKey.WORD_RIGHT, TerminalInputAction.MOVE_WORD_RIGHT);
        bindings.put(TerminalKey.UP, TerminalInputAction.PREVIOUS_HISTORY);
        bindings.put(TerminalKey.DOWN, TerminalInputAction.NEXT_HISTORY);
        bindings.put(TerminalKey.CTRL_O, TerminalInputAction.TOGGLE_TOOL_OUTPUT_EXPANDED);
        bindings.put(TerminalKey.EXPAND_TOOLS, TerminalInputAction.EXPAND_TOOLS);
        return new KeyBindingRegistry(bindings);
    }

    static KeyBindingRegistry fromAliases(Map<String, String> aliases) {
        Map<TerminalKey, TerminalInputAction> bindings = new EnumMap<>(defaults().bindings);
        for (Map.Entry<String, String> alias : aliases.entrySet()) {
            TerminalKey key = keyAlias(alias.getKey());
            TerminalInputAction action = actionAlias(alias.getValue());
            if (key != null && action != null) {
                bindings.put(key, action);
            }
        }
        return new KeyBindingRegistry(bindings);
    }

    TerminalInputAction actionFor(TerminalKey key) {
        return bindings.getOrDefault(key, TerminalInputAction.NOOP);
    }

    private static TerminalKey keyAlias(String alias) {
        return switch (normalize(alias)) {
            case "backspace" -> TerminalKey.BACKSPACE;
            case "alt+backspace" -> TerminalKey.ALT_BACKSPACE;
            case "alt+delete" -> TerminalKey.ALT_DELETE;
            case "togglethinking" -> TerminalKey.CTRL_O;
            case "expandtools" -> TerminalKey.EXPAND_TOOLS;
            default -> null;
        };
    }

    private static TerminalInputAction actionAlias(String alias) {
        return switch (normalize(alias)) {
            case "deletepreviouscharacter" -> TerminalInputAction.DELETE_PREVIOUS_CHARACTER;
            case "deletepreviousword" -> TerminalInputAction.DELETE_PREVIOUS_WORD;
            case "deletenextword" -> TerminalInputAction.DELETE_NEXT_WORD;
            case "togglethinking" -> TerminalInputAction.TOGGLE_THINKING;
            case "toggletooloutputexpanded" -> TerminalInputAction.TOGGLE_TOOL_OUTPUT_EXPANDED;
            case "expandtools" -> TerminalInputAction.EXPAND_TOOLS;
            case "insertnewline" -> TerminalInputAction.INSERT_NEWLINE;
            default -> null;
        };
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }
}
