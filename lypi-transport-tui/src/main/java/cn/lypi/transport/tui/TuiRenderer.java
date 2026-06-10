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
    private static final String INPUT_PREFIX = "> ";
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
        InputBlock inputBlock = layoutInput(input, cursor, layout);
        int transcriptHeight = layout.transcriptHeight(inputBlock.lines().size());
        List<String> rawOverlay = overlayLines == null ? List.of() : overlayLines.stream()
            .map(line -> AnsiWidth.truncate(line, layout.width()))
            .toList();
        int overlayBudget = Math.max(0, transcriptHeight - 1);
        List<String> overlay = rawOverlay.stream()
            .limit(overlayBudget)
            .toList();
        int visibleTranscriptBudget = Math.max(1, transcriptHeight - overlay.size());
        screen.updateViewportHeight(Math.max(1, visibleTranscriptBudget));
        screen.setTranscript(transcript);

        List<String> lines = new ArrayList<>();
        lines.addAll(screen.visibleTranscript());
        lines.addAll(overlay);
        lines.addAll(inputBlock.lines());
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

    private InputBlock layoutInput(String input, int cursor, TuiLayout layout) {
        String value = input == null ? "" : input;
        int boundedCursor = cursor < 0 ? -1 : Math.max(0, Math.min(cursor, value.length()));
        int maxHeight = layout.maxInputHeight();
        List<String> lines = new ArrayList<>();
        StringBuilder currentLine = new StringBuilder();
        int currentWidth = 0;
        int cursorLine = 0;
        int cursorColumn = -1;

        if (boundedCursor == 0) {
            cursorColumn = 0;
        }
        for (int index = 0; index < value.length();) {
            if (boundedCursor == index) {
                cursorLine = lines.size();
                cursorColumn = currentWidth;
            }
            int codePoint = value.codePointAt(index);
            String chunk = new String(Character.toChars(codePoint));
            int chunkWidth = AnsiWidth.displayWidth(chunk);
            int availableWidth = lines.isEmpty()
                ? Math.max(1, layout.width() - AnsiWidth.displayWidth(INPUT_PREFIX))
                : Math.max(1, layout.width());
            if (codePoint == '\n') {
                lines.add(currentLine.toString());
                currentLine = new StringBuilder();
                currentWidth = 0;
                index += Character.charCount(codePoint);
                if (boundedCursor == index) {
                    cursorLine = lines.size();
                    cursorColumn = 0;
                }
                continue;
            }
            if (currentWidth > 0 && currentWidth + chunkWidth > availableWidth) {
                lines.add(currentLine.toString());
                currentLine = new StringBuilder();
                currentWidth = 0;
                availableWidth = Math.max(1, layout.width());
                if (boundedCursor == index) {
                    cursorLine = lines.size();
                    cursorColumn = 0;
                }
            }
            currentLine.appendCodePoint(codePoint);
            currentWidth += chunkWidth;
            index += Character.charCount(codePoint);
        }
        if (boundedCursor == value.length()) {
            cursorLine = lines.size();
            cursorColumn = currentWidth;
        }
        lines.add(currentLine.toString());

        int start = Math.max(0, lines.size() - maxHeight);
        if (cursorLine < start) {
            start = cursorLine;
        }
        List<String> visibleContent = new ArrayList<>(lines.subList(start, lines.size()));
        if (visibleContent.isEmpty()) {
            visibleContent.add("");
        }
        int visibleCursorLine = cursor < 0 ? -1 : cursorLine - start;
        List<String> rendered = new ArrayList<>(visibleContent.size());
        for (int i = 0; i < visibleContent.size(); i++) {
            String prefix = i == 0 && start == 0 ? INPUT_PREFIX : "";
            String content = visibleContent.get(i);
            if (visibleCursorLine == i) {
                rendered.add(applyInputBackground(insertCursor(content, cursorColumn, prefix)));
                continue;
            }
            rendered.add(applyInputBackground(prefix + content));
        }
        if (cursor >= 0 && visibleCursorLine == visibleContent.size()) {
            rendered.add(applyInputBackground(insertCursor("", 0, "")));
        }
        return new InputBlock(rendered);
    }

    private String insertCursor(String content, int cursorColumn, String prefix) {
        StringBuilder visible = new StringBuilder(prefix);
        int width = 0;
        if (cursorColumn <= 0) {
            visible.append(TerminalFrameRenderer.CURSOR_MARKER).append(content);
            return visible.toString();
        }
        for (int index = 0; index < content.length();) {
            if (width == cursorColumn) {
                visible.append(TerminalFrameRenderer.CURSOR_MARKER);
            }
            int codePoint = content.codePointAt(index);
            String chunk = new String(Character.toChars(codePoint));
            visible.append(chunk);
            width += AnsiWidth.displayWidth(chunk);
            index += Character.charCount(codePoint);
        }
        if (width == cursorColumn) {
            visible.append(TerminalFrameRenderer.CURSOR_MARKER);
        }
        return visible.toString();
    }

    private String applyInputBackground(String line) {
        return INPUT_BACKGROUND + line + ANSI_RESET;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private record InputBlock(List<String> lines) {
    }
}
