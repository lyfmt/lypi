package cn.lypi.transport.tui;

import cn.lypi.contracts.tui.StatusBarState;
import cn.lypi.contracts.tui.TuiBlock;
import cn.lypi.contracts.tui.TuiErrorBlock;
import cn.lypi.contracts.tui.TuiMessageBlock;
import cn.lypi.contracts.tui.TuiThinkingBlock;
import cn.lypi.contracts.tui.TuiToolBlock;
import cn.lypi.contracts.tui.TuiViewModel;
import java.util.ArrayList;
import java.util.List;

final class TuiRenderer {
    List<String> render(TuiViewModel view, TuiScreen screen, TuiLayout layout, String input) {
        List<String> transcript = transcriptLines(view.blocks(), layout.width());
        screen.setTranscript(transcript);

        List<String> lines = new ArrayList<>();
        List<String> visible = screen.visibleTranscript();
        for (int i = 0; i < layout.transcriptHeight(); i++) {
            lines.add(i < visible.size() ? visible.get(i) : "");
        }
        lines.add(statusLine(view.statusBar(), screen, layout.width()));
        lines.add(AnsiWidth.truncate("> " + (input == null ? "" : input), layout.width()));
        return lines;
    }

    private List<String> transcriptLines(List<TuiBlock> blocks, int width) {
        List<String> lines = new ArrayList<>();
        for (TuiBlock block : blocks) {
            String text = switch (block) {
                case TuiMessageBlock message -> message.role() + ": " + message.content();
                case TuiThinkingBlock thinking -> thinking.collapsed()
                    ? "thinking: collapsed"
                    : "thinking: " + thinking.content();
                case TuiToolBlock tool -> "tool " + tool.toolName() + ": " + tool.label();
                case TuiErrorBlock error -> "error: " + error.message();
            };
            lines.addAll(wrap(text, width));
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

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
