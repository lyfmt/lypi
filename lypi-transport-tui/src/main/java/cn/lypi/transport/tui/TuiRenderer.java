package cn.lypi.transport.tui;

import cn.lypi.contracts.security.PermissionOption;
import cn.lypi.contracts.tui.PermissionPromptView;
import cn.lypi.contracts.tui.StatusBarState;
import cn.lypi.contracts.tui.TuiBlock;
import cn.lypi.contracts.tui.TuiErrorBlock;
import cn.lypi.contracts.tui.TuiMessageBlock;
import cn.lypi.contracts.tui.TuiThinkingBlock;
import cn.lypi.contracts.tui.TuiToolBlock;
import cn.lypi.contracts.tui.TuiViewModel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class TuiRenderer {
    private static final String INPUT_BACKGROUND = "\033[48;5;236m";
    private static final String USER_MESSAGE = "\033[38;5;81m";
    private static final String THINKING_MESSAGE = "\033[38;5;244m";
    private static final String INPUT_BORDER = "\033[38;5;240m";
    private static final String INPUT_CURSOR = "\033[38;5;81m|\033[39m";
    private static final String ANSI_RESET = "\033[0m";
    private static final String INPUT_PREFIX = "> ";
    private static final int THINKING_VISIBLE_LINE_LIMIT = 3;
    private final MarkdownRenderer markdownRenderer = new MarkdownRenderer();
    private final ToolDisplayRendererRegistry toolDisplayRenderers = ToolDisplayRendererRegistry.defaults();

    List<String> render(TuiViewModel view, TuiScreen screen, TuiLayout layout, String input) {
        return renderFrame(view, screen, layout, input, -1).lines();
    }

    List<String> render(TuiViewModel view, TuiScreen screen, TuiLayout layout, String input, int cursor) {
        return renderFrame(view, screen, layout, input, cursor, List.of()).lines();
    }

    List<String> render(
        TuiViewModel view,
        TuiScreen screen,
        TuiLayout layout,
        String input,
        int cursor,
        List<String> overlayLines
    ) {
        return renderFrame(view, screen, layout, input, cursor, overlayLines).lines();
    }

    TuiRenderFrame renderFrame(TuiViewModel view, TuiScreen screen, TuiLayout layout, String input, int cursor) {
        return renderFrame(view, screen, layout, input, cursor, List.of());
    }

    TuiRenderFrame renderFrame(
        TuiViewModel view,
        TuiScreen screen,
        TuiLayout layout,
        String input,
        int cursor,
        List<String> overlayLines
    ) {
        return renderFrame(view, screen, layout, input, cursor, overlayLines, false);
    }

    TuiRenderFrame renderFrame(
        TuiViewModel view,
        TuiScreen screen,
        TuiLayout layout,
        String input,
        int cursor,
        List<String> overlayLines,
        boolean toolOutputExpanded
    ) {
        InputBlock inputBlock = compactRunning(view)
            ? readonlyRuntimeInputBlock("compact 正在进行...", layout)
            : layoutInput(input, cursor, layout);
        List<String> permissionOverlay = permissionOverlayLines(view, layout.width());
        List<String> externalOverlay = overlayLines == null ? List.of() : overlayLines.stream()
            .map(line -> AnsiWidth.truncate(line, layout.width()))
            .toList();
        List<String> overlay = new ArrayList<>(permissionOverlay.size() + externalOverlay.size());
        overlay.addAll(permissionOverlay);
        overlay.addAll(externalOverlay);
        int chromeLineCount = inputBlock.lines().size() + overlay.size() + 1;
        int transcriptLineBudget = Math.max(0, layout.height() - chromeLineCount);
        int effectiveTranscriptBudget = toolOutputExpanded ? transcriptLineBudget : Integer.MAX_VALUE;
        List<String> transcript = transcriptLines(view, layout.width(), toolOutputExpanded, effectiveTranscriptBudget);
        screen.setTranscript(transcript);

        List<String> lines = new ArrayList<>();
        lines.addAll(transcript);
        lines.addAll(inputBlock.lines());
        lines.addAll(overlay);
        lines.add(statusLine(view.statusBar(), screen, layout.width()));
        return new TuiRenderFrame(lines, chromeLineCount);
    }

    private List<String> transcriptLines(List<TuiBlock> blocks, int width, boolean toolOutputExpanded) {
        return transcriptLines(blocks, width, toolOutputExpanded, Integer.MAX_VALUE);
    }

    private List<String> transcriptLines(List<TuiBlock> blocks, int width, boolean toolOutputExpanded, int lineBudget) {
        List<String> lines = new ArrayList<>();
        for (int index = 0; index < blocks.size(); index++) {
            if (lines.size() >= lineBudget) {
                break;
            }
            TuiBlock block = blocks.get(index);
            if (block instanceof TuiToolBlock tool
                && !toolOutputExpanded
                && toolDisplayRenderers.isReadLikeTool(tool)) {
                List<TuiToolBlock> group = new ArrayList<>();
                group.add(tool);
                while (index + 1 < blocks.size()
                    && blocks.get(index + 1) instanceof TuiToolBlock nextTool
                    && toolDisplayRenderers.isReadLikeTool(nextTool)) {
                    group.add(nextTool);
                    index++;
                }
                appendWithinBudget(lines, readLikeToolSummaryLines(group, width), lineBudget);
                continue;
            }
            String text = switch (block) {
                case TuiMessageBlock message -> null;
                case TuiThinkingBlock thinking -> null;
                case TuiToolBlock tool -> null;
                case TuiErrorBlock error -> "error: " + error.message();
            };
            if (block instanceof TuiMessageBlock message) {
                if ("user".equalsIgnoreCase(message.role())) {
                    appendWithinBudget(lines, styledLines(prefixedLines("user: ", message.content(), width), USER_MESSAGE), lineBudget);
                } else {
                    appendWithinBudget(lines, markdownRenderer.render(message.content(), width), lineBudget);
                }
            } else if (block instanceof TuiToolBlock tool) {
                appendWithinBudget(lines, toolLines(tool, width, toolOutputExpanded, remainingBudget(lines, lineBudget)), lineBudget);
            } else if (block instanceof TuiThinkingBlock thinking) {
                String content = thinking.collapsed() ? "collapsed" : thinking.content();
                List<String> thinkingLines = prefixedLines("thinking: ", content, width);
                appendWithinBudget(
                    lines,
                    styledLines(compressedThinkingLines(thinkingLines), THINKING_MESSAGE),
                    lineBudget
                );
            } else {
                appendWithinBudget(lines, wrap(text, width), lineBudget);
            }
        }
        return lines;
    }

    private List<String> readLikeToolSummaryLines(List<TuiToolBlock> tools, int width) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (TuiToolBlock tool : tools) {
            counts.merge(tool.toolName(), 1, Integer::sum);
        }
        String summary = counts.entrySet().stream()
            .map(entry -> entry.getKey() + " x" + entry.getValue())
            .collect(java.util.stream.Collectors.joining(", "));
        return wrap("tools: " + summary + " (Ctrl+O details)", width);
    }

    private List<String> transcriptLines(TuiViewModel view, int width, boolean toolOutputExpanded, int lineBudget) {
        List<String> lines = transcriptLines(view.blocks(), width, toolOutputExpanded, lineBudget);
        view.diffView().ifPresent(diff -> new DiffOverlay(diff)
            .lines()
            .forEach(line -> appendWithinBudget(lines, wrap(line, width), lineBudget)));
        if (view.runtimeLine() != null && !view.runtimeLine().isBlank()) {
            appendWithinBudget(lines, wrap("· " + view.runtimeLine(), width), lineBudget);
        }
        return lines;
    }

    private List<String> permissionOverlayLines(TuiViewModel view, int width) {
        if (view.permissionPrompt().isEmpty()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        PermissionPromptView prompt = view.permissionPrompt().orElseThrow();
        appendPrefixedMultiline(lines, "permission " + prompt.toolUseId() + ": ", prompt.reason(), width, Integer.MAX_VALUE);
        if (!prompt.rule().isBlank()) {
            appendPrefixedMultiline(lines, "rule: ", prompt.rule(), width, Integer.MAX_VALUE);
        }
        for (PermissionOption option : prompt.options()) {
            String prefix = option.optionId().equals(prompt.selectedOptionId()) ? "> " : "  ";
            appendWithinBudget(lines, wrap(prefix + optionLabel(option), width), Integer.MAX_VALUE);
        }
        return lines;
    }

    private void appendPrefixedMultiline(
        List<String> lines,
        String prefix,
        String text,
        int width,
        int lineBudget
    ) {
        String safe = text == null ? "" : text;
        String[] logicalLines = safe.split("\\R", -1);
        if (logicalLines.length == 0) {
            appendWithinBudget(lines, wrap(prefix, width), lineBudget);
            return;
        }
        for (int index = 0; index < logicalLines.length; index++) {
            String logicalLine = logicalLines[index];
            String rendered = index == 0 ? prefix + logicalLine : logicalLine;
            appendWithinBudget(lines, wrap(rendered, width), lineBudget);
        }
    }

    private String optionLabel(PermissionOption option) {
        String label = option.label().isBlank() ? option.optionId() : option.label();
        return option.description().isBlank() ? label : label + " - " + option.description();
    }

    private List<String> toolLines(TuiToolBlock tool, int width, boolean toolOutputExpanded) {
        return toolLines(tool, width, toolOutputExpanded, Integer.MAX_VALUE);
    }

    private List<String> toolLines(TuiToolBlock tool, int width, boolean toolOutputExpanded, int lineBudget) {
        List<String> lines = new ArrayList<>();
        int detailLineLimit = Math.max(0, lineBudget - 1);
        ToolDisplayModel model = toolDisplayRenderers.render(tool, toolOutputExpanded, detailLineLimit);
        appendWithinBudget(lines, wrap(model.title(), width), lineBudget);
        for (String summaryLine : model.summaryLines()) {
            if (!summaryLine.isBlank()) {
                appendWithinBudget(lines, wrap("  " + summaryLine, width), lineBudget);
            }
        }
        for (String detailLine : model.previewLines()) {
            appendWithinBudget(lines, wrap("  " + detailLine, width), lineBudget);
        }
        return lines;
    }

    private int remainingBudget(List<String> lines, int lineBudget) {
        return Math.max(0, lineBudget - lines.size());
    }

    private void appendWithinBudget(List<String> target, List<String> source, int lineBudget) {
        if (target.size() >= lineBudget) {
            return;
        }
        int remaining = lineBudget - target.size();
        target.addAll(source.subList(0, Math.min(remaining, source.size())));
    }

    private List<String> limitLines(List<String> lines, int limit) {
        return lines.subList(0, Math.min(limit, lines.size()));
    }

    private List<String> compressedThinkingLines(List<String> lines) {
        if (lines.size() <= THINKING_VISIBLE_LINE_LIMIT) {
            return lines;
        }
        List<String> compressed = new ArrayList<>(limitLines(lines, THINKING_VISIBLE_LINE_LIMIT));
        int hiddenLineCount = lines.size() - THINKING_VISIBLE_LINE_LIMIT;
        compressed.add(" ".repeat(AnsiWidth.displayWidth("thinking: ")) + "... 已折叠 " + hiddenLineCount + " 行");
        return compressed;
    }

    private List<String> prefixedLines(String prefix, String content, int width) {
        List<String> lines = new ArrayList<>();
        String value = content == null ? "" : content;
        String[] contentLines = value.split("\\R", -1);
        String continuationPrefix = " ".repeat(AnsiWidth.displayWidth(prefix));
        for (int index = 0; index < contentLines.length; index++) {
            String linePrefix = index == 0 ? prefix : continuationPrefix;
            lines.addAll(wrap(linePrefix + contentLines[index], width));
        }
        return lines;
    }

    private List<String> styledLines(List<String> lines, String style) {
        return lines.stream()
            .map(line -> style + line + ANSI_RESET)
            .toList();
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
            List.of(
                nullToEmpty(status.sessionId()),
                nullToEmpty(status.model()),
                nullToEmpty(status.mode()),
                nullToEmpty(status.permissionMode()),
                nullToEmpty(status.approvalMode()),
                nullToEmpty(status.activePermissionProfileId())
            )
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

    private boolean compactRunning(TuiViewModel view) {
        return view != null && view.runtimeLine() != null && view.runtimeLine().startsWith("compacting");
    }

    private InputBlock readonlyRuntimeInputBlock(String text, TuiLayout layout) {
        int width = layout.width();
        String content = AnsiWidth.truncate(text == null ? "" : text, width);
        return new InputBlock(List.of(INPUT_BACKGROUND + content + ANSI_RESET));
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
