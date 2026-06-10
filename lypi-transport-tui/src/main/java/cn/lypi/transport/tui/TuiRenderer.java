package cn.lypi.transport.tui;

import cn.lypi.contracts.security.PermissionOption;
import cn.lypi.contracts.tui.StatusBarState;
import cn.lypi.contracts.tui.TuiBlock;
import cn.lypi.contracts.tui.TuiErrorBlock;
import cn.lypi.contracts.tui.TuiMessageBlock;
import cn.lypi.contracts.tui.TuiThinkingBlock;
import cn.lypi.contracts.tui.TuiToolBlock;
import cn.lypi.contracts.tui.TuiViewModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class TuiRenderer {
    private static final String INPUT_BACKGROUND = "\033[48;5;236m";
    private static final String ANSI_RESET = "\033[0m";
    private final MarkdownRenderer markdownRenderer = new MarkdownRenderer();

    List<String> render(TuiViewModel view, TuiScreen screen, TuiLayout layout, String input) {
        return render(view, screen, layout, input, -1);
    }

    List<String> render(TuiViewModel view, TuiScreen screen, TuiLayout layout, String input, int cursor) {
        return render(view, screen, layout, input, cursor, List.of());
    }

    List<String> render(
        TuiViewModel view,
        TuiScreen screen,
        TuiLayout layout,
        String input,
        int cursor,
        List<String> overlayLines
    ) {
        List<String> transcript = transcriptLines(view, layout.width());
        screen.setTranscript(transcript);

        List<String> lines = new ArrayList<>();
        List<String> visible = screen.visibleTranscript();
        List<String> overlay = overlayLines == null ? List.of() : overlayLines.stream()
            .limit(Math.max(0, layout.transcriptHeight()))
            .map(line -> AnsiWidth.truncate(line, layout.width()))
            .toList();
        int transcriptHeight = Math.max(0, layout.transcriptHeight() - overlay.size());
        for (int i = 0; i < transcriptHeight; i++) {
            lines.add(i < visible.size() ? visible.get(i) : "");
        }
        lines.addAll(overlay);
        lines.add(inputLine(input, cursor, layout.width()));
        lines.add(statusLine(view.statusBar(), screen, layout.width()));
        return lines;
    }

    private List<String> transcriptLines(List<TuiBlock> blocks, int width) {
        List<String> lines = new ArrayList<>();
        for (TuiBlock block : blocks) {
            String text = switch (block) {
                case TuiMessageBlock message -> null;
                case TuiThinkingBlock thinking -> thinking.collapsed()
                    ? "thinking: collapsed"
                    : "thinking: " + thinking.content();
                case TuiToolBlock tool -> null;
                case TuiErrorBlock error -> "error: " + error.message();
            };
            if (block instanceof TuiMessageBlock message) {
                lines.addAll(markdownRenderer.render(message.content(), width));
            } else if (block instanceof TuiToolBlock tool) {
                lines.addAll(toolLines(tool, width));
            } else {
                lines.addAll(wrap(text, width));
            }
        }
        return lines;
    }

    private List<String> transcriptLines(TuiViewModel view, int width) {
        List<String> lines = transcriptLines(view.blocks(), width);
        view.permissionPrompt().ifPresent(prompt -> {
            lines.addAll(wrap("permission " + prompt.toolUseId() + ": " + prompt.reason(), width));
            if (!prompt.rule().isBlank()) {
                lines.addAll(wrap("rule: " + prompt.rule(), width));
            }
            for (PermissionOption option : prompt.options()) {
                String prefix = option.optionId().equals(prompt.selectedOptionId()) ? "> " : "  ";
                lines.addAll(wrap(prefix + optionLabel(option), width));
            }
        });
        view.diffView().ifPresent(diff -> new DiffOverlay(diff)
            .lines()
            .forEach(line -> lines.addAll(wrap(line, width))));
        if (view.runtimeLine() != null && !view.runtimeLine().isBlank()) {
            lines.addAll(wrap("· " + view.runtimeLine(), width));
        }
        return lines;
    }

    private String optionLabel(PermissionOption option) {
        String label = option.label().isBlank() ? option.optionId() : option.label();
        return option.description().isBlank() ? label : label + " - " + option.description();
    }

    private List<String> toolLines(TuiToolBlock tool, int width) {
        List<String> lines = new ArrayList<>();
        lines.addAll(wrap("tool " + tool.state().name().toLowerCase(Locale.ROOT) + " " + tool.toolName() + ": " + tool.label(), width));
        if (tool.details() == null || tool.details().isBlank()) {
            return lines;
        }
        for (String detailLine : tool.details().split("\\R", -1)) {
            if (detailLine.isBlank()) {
                continue;
            }
            lines.addAll(wrap("  " + detailLine, width));
        }
        return lines;
    }

    private List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int currentWidth = 0;
        for (int index = 0; index < text.length();) {
            int codePoint = text.codePointAt(index);
            int codePointWidth = AnsiWidth.displayWidth(new String(Character.toChars(codePoint)));
            if (currentWidth > 0 && currentWidth + codePointWidth > width) {
                lines.add(current.toString());
                current = new StringBuilder();
                currentWidth = 0;
            }
            current.appendCodePoint(codePoint);
            currentWidth += codePointWidth;
            index += Character.charCount(codePoint);
        }
        lines.add(current.toString());
        return lines;
    }

    private String statusLine(StatusBarState status, TuiScreen screen, int width) {
        String scroll = screen.linesBelow() > 0 ? " scroll +" + screen.linesBelow() : "";
        String full = String.join(
            " ",
            List.of(nullToEmpty(status.sessionId()), nullToEmpty(status.model()), nullToEmpty(status.mode()), nullToEmpty(status.permissionMode()))
        ).trim() + scroll;
        if (AnsiWidth.displayWidth(full) <= width) {
            return full;
        }
        if (status.permissionMode() != null && status.permissionMode().contains("tool")) {
            return AnsiWidth.truncate("tool " + status.permissionMode() + scroll, width);
        }
        return AnsiWidth.truncate(full, width);
    }

    private String inputLine(String input, int cursor, int width) {
        return INPUT_BACKGROUND + rawInputLine(input, cursor, width) + ANSI_RESET;
    }

    private String rawInputLine(String input, int cursor, int width) {
        String value = input == null ? "" : input;
        if (cursor < 0) {
            return AnsiWidth.truncate("> " + value, width);
        }
        if (width <= 0) {
            return TerminalFrameRenderer.CURSOR_MARKER;
        }
        int boundedCursor = Math.max(0, Math.min(cursor, value.length()));
        String prefix = width >= 2 ? "> " : "";
        int beforeBudget = Math.max(0, width - AnsiWidth.displayWidth(prefix) - 1);
        String before = value.substring(0, boundedCursor);
        String visibleBefore = before;
        if (AnsiWidth.displayWidth(visibleBefore) > beforeBudget) {
            visibleBefore = beforeBudget <= 1
                ? ""
                : "…" + displaySuffix(before, beforeBudget - 1);
        }
        int remaining = Math.max(0, width - AnsiWidth.displayWidth(prefix) - AnsiWidth.displayWidth(visibleBefore));
        String visibleAfter = displayPrefix(value.substring(boundedCursor), remaining);
        return prefix + visibleBefore + TerminalFrameRenderer.CURSOR_MARKER + visibleAfter;
    }

    private String displayPrefix(String value, int maxWidth) {
        if (maxWidth <= 0 || value.isEmpty()) {
            return "";
        }
        if (AnsiWidth.displayWidth(value) <= maxWidth) {
            return value;
        }
        int contentLimit = Math.max(0, maxWidth - 1);
        StringBuilder result = new StringBuilder();
        int width = 0;
        for (int index = 0; index < value.length();) {
            int codePoint = value.codePointAt(index);
            int codePointWidth = AnsiWidth.displayWidth(new String(Character.toChars(codePoint)));
            if (width + codePointWidth > contentLimit) {
                break;
            }
            result.appendCodePoint(codePoint);
            width += codePointWidth;
            index += Character.charCount(codePoint);
        }
        return result.append("…").toString();
    }

    private String displaySuffix(String value, int maxWidth) {
        if (maxWidth <= 0 || value.isEmpty()) {
            return "";
        }
        if (AnsiWidth.displayWidth(value) <= maxWidth) {
            return value;
        }
        StringBuilder reversed = new StringBuilder();
        int width = 0;
        for (int index = value.length(); index > 0;) {
            int codePoint = value.codePointBefore(index);
            int codePointWidth = AnsiWidth.displayWidth(new String(Character.toChars(codePoint)));
            if (width + codePointWidth > maxWidth) {
                break;
            }
            reversed.appendCodePoint(codePoint);
            width += codePointWidth;
            index -= Character.charCount(codePoint);
        }
        return reversed.reverse().toString();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
