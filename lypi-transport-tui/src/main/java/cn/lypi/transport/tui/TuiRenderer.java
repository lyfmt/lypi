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
    private static final String INPUT_BORDER = "\033[38;5;240m";
    private static final String INPUT_CURSOR = "\033[38;5;81m|\033[39m";
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
        int transcriptHeight = layout.transcriptHeight(inputBlock.height());

        List<String> lines = new ArrayList<>();
        List<String> overlay = overlayLines == null ? List.of() : overlayLines.stream()
            .map(line -> AnsiWidth.truncate(line, layout.width()))
            .toList();
        screen.updateViewportHeight(transcriptHeight);
        screen.setTranscript(transcript);
        List<String> visibleTranscript = screen.visibleTranscript();
        lines.addAll(blankLines(transcriptHeight - visibleTranscript.size()));
        lines.addAll(visibleTranscript);
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
        String full = String.join(
            " ",
            List.of(nullToEmpty(status.sessionId()), nullToEmpty(status.model()), nullToEmpty(status.mode()), nullToEmpty(status.permissionMode()))
        ).trim();
        if (AnsiWidth.displayWidth(full) <= width) {
            return full;
        }
        if (status.permissionMode() != null && status.permissionMode().contains("tool")) {
            return AnsiWidth.truncate("tool " + status.permissionMode(), width);
        }
        return AnsiWidth.truncate(full, width);
    }

    private InputBlock layoutInput(String input, int cursor, TuiLayout layout) {
        String value = input == null ? "" : input;
        int width = layout.width();
        int boundedCursor = Math.max(0, Math.min(cursor, value.length()));
        boolean showCursor = cursor >= 0;
        List<InputVisualLine> visualLines = visualInputLines(value, boundedCursor, showCursor, width);
        int maxBlockRows = layout.maxInputBlockHeight();
        int maxContentRows = Math.min(maxVisibleInputContentRows(layout), visualLines.size());
        int start = Math.max(0, visualLines.size() - maxContentRows);
        if (showCursor) {
            int cursorLine = cursorLine(visualLines);
            if (cursorLine < start) {
                start = cursorLine;
            } else if (cursorLine >= start + maxContentRows) {
                start = Math.max(0, cursorLine - maxContentRows + 1);
            }
        }
        int end = Math.min(visualLines.size(), start + maxContentRows);

        List<String> lines = new ArrayList<>();
        boolean renderBothBorders = maxBlockRows >= 3;
        boolean renderAnyBorder = maxBlockRows > maxContentRows;
        if (renderBothBorders || maxBlockRows == 2) {
            lines.add(inputBorder(width));
        }
        for (int index = start; index < end; index++) {
            InputVisualLine line = visualLines.get(index);
            String prefix = index == 0 ? INPUT_PREFIX : "";
            lines.add(INPUT_BACKGROUND + inputContentLine(line, prefix) + ANSI_RESET);
        }
        if (renderBothBorders) {
            lines.add(inputBorder(width));
        } else if (renderAnyBorder && lines.size() < maxBlockRows) {
            lines.add(inputBorder(width));
        }
        return new InputBlock(lines);
    }

    private List<InputVisualLine> visualInputLines(String value, int cursor, boolean showCursor, int width) {
        List<InputVisualLine> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int currentWidth = 0;
        int cursorLine = 0;
        int cursorColumn = 0;
        boolean cursorSeen = false;
        int cursorReserveWidth = showCursor ? AnsiWidth.displayWidth(INPUT_CURSOR) : 0;

        for (int index = 0; index < value.length();) {
            int codePoint = value.codePointAt(index);
            if (codePoint == '\n') {
                if (showCursor && cursor == index) {
                    cursorLine = lines.size();
                    cursorColumn = currentWidth;
                    cursorSeen = true;
                }
                lines.add(new InputVisualLine(current.toString(), cursorSeen && cursorLine == lines.size(), cursorColumn));
                current = new StringBuilder();
                currentWidth = 0;
                index += Character.charCount(codePoint);
                if (showCursor && cursor == index) {
                    cursorLine = lines.size();
                    cursorColumn = 0;
                    cursorSeen = true;
                }
                continue;
            }

            String chunk = new String(Character.toChars(codePoint));
            int chunkWidth = AnsiWidth.displayWidth(chunk);
            int availableWidth = lines.isEmpty()
                ? Math.max(1, width - AnsiWidth.displayWidth(INPUT_PREFIX) - cursorReserveWidth)
                : Math.max(1, width - cursorReserveWidth);
            if (currentWidth > 0 && currentWidth + chunkWidth > availableWidth) {
                if (showCursor && cursor == index) {
                    cursorLine = lines.size();
                    cursorColumn = currentWidth;
                    cursorSeen = true;
                }
                lines.add(new InputVisualLine(current.toString(), cursorSeen && cursorLine == lines.size(), cursorColumn));
                current = new StringBuilder();
                currentWidth = 0;
            }
            if (showCursor && cursor == index) {
                cursorLine = lines.size();
                cursorColumn = currentWidth;
                cursorSeen = true;
            }
            current.appendCodePoint(codePoint);
            currentWidth += chunkWidth;
            index += Character.charCount(codePoint);
        }

        if (showCursor && cursor == value.length()) {
            cursorLine = lines.size();
            cursorColumn = currentWidth;
            cursorSeen = true;
        }
        lines.add(new InputVisualLine(current.toString(), cursorSeen && cursorLine == lines.size(), cursorColumn));
        return lines;
    }

    private int maxVisibleInputContentRows(TuiLayout layout) {
        int maxContentRows = layout.maxInputContentHeight();
        if (layout.maxInputBlockHeight() <= 2) {
            return Math.max(1, layout.maxInputBlockHeight() - 1);
        }
        return maxContentRows;
    }

    private int cursorLine(List<InputVisualLine> lines) {
        for (int index = 0; index < lines.size(); index++) {
            if (lines.get(index).hasCursor()) {
                return index;
            }
        }
        return Math.max(0, lines.size() - 1);
    }

    private String inputContentLine(InputVisualLine line, String prefix) {
        if (!line.hasCursor()) {
            return prefix + line.content();
        }
        return prefix + insertCursor(line.content(), line.cursorColumn());
    }

    private String insertCursor(String content, int cursorColumn) {
        if (cursorColumn <= 0) {
            return TerminalFrameRenderer.CURSOR_MARKER + INPUT_CURSOR + content;
        }
        StringBuilder result = new StringBuilder();
        int width = 0;
        boolean inserted = false;
        for (int index = 0; index < content.length();) {
            if (!inserted && width >= cursorColumn) {
                result.append(TerminalFrameRenderer.CURSOR_MARKER).append(INPUT_CURSOR);
                inserted = true;
            }
            int codePoint = content.codePointAt(index);
            String chunk = new String(Character.toChars(codePoint));
            result.append(chunk);
            width += AnsiWidth.displayWidth(chunk);
            index += Character.charCount(codePoint);
        }
        if (!inserted) {
            result.append(TerminalFrameRenderer.CURSOR_MARKER).append(INPUT_CURSOR);
        }
        return result.toString();
    }

    private String inputBorder(int width) {
        return INPUT_BORDER + "─".repeat(width) + ANSI_RESET;
    }

    private List<String> blankLines(int count) {
        if (count <= 0) {
            return List.of();
        }
        return java.util.Collections.nCopies(count, "");
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private record InputBlock(List<String> lines) {
        int height() {
            return lines.size();
        }
    }

    private record InputVisualLine(String content, boolean hasCursor, int cursorColumn) {
    }
}
