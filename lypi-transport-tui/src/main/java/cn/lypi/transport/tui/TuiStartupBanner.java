package cn.lypi.transport.tui;

import java.util.ArrayList;
import java.util.List;

final class TuiStartupBanner {
    private static final int FULL_BANNER_MIN_WIDTH = 46;
    private static final String ANSI_RESET = "\033[0m";
    private static final String PRIMARY = "\033[38;5;81m";
    private static final String ACCENT = "\033[38;5;213m";
    private static final String DIM = "\033[38;5;244m";
    private static final String BOLD = "\033[1m";
    private static final List<String> FULL_ARTWORK = List.of(
        DIM + "╭────────────────────────────────────────────────────────╮" + ANSI_RESET,
        PRIMARY + BOLD + "██╗      ██╗   ██╗      ██████╗  ██╗" + ANSI_RESET,
        PRIMARY + BOLD + "██║      ╚██╗ ██╔╝      ██╔══██╗ ██║" + ANSI_RESET,
        ACCENT + BOLD + "██║       ╚████╔╝ █████╗██████╔╝ ██║" + ANSI_RESET,
        ACCENT + BOLD + "██║        ╚██╔╝  ╚════╝██╔═══╝  ██║" + ANSI_RESET,
        PRIMARY + BOLD + "███████╗    ██║         ██║      ██║" + ANSI_RESET,
        DIM + "╰──────────────────────── LY-PI ────────────────────────╯" + ANSI_RESET,
        ACCENT + "coding agent cockpit" + ANSI_RESET
    );
    private static final List<String> COMPACT_ARTWORK = List.of(
        PRIMARY + BOLD + "LY-PI" + ANSI_RESET,
        ACCENT + "coding agent" + ANSI_RESET
    );

    private TuiStartupBanner() {
    }

    static List<TerminalLine> render(int width, int availableRows) {
        List<String> artwork = width >= FULL_BANNER_MIN_WIDTH ? FULL_ARTWORK : COMPACT_ARTWORK;
        int lineCount = Math.max(availableRows, artwork.size());
        List<TerminalLine> lines = new ArrayList<>(lineCount);
        int topPadding = Math.max(0, (lineCount - artwork.size()) / 2);
        for (int index = 0; index < topPadding; index++) {
            lines.add(new TerminalLine(""));
        }
        artwork.stream()
            .map(line -> center(line, width))
            .forEach(lines::add);
        while (lines.size() < lineCount) {
            lines.add(new TerminalLine(""));
        }
        return List.copyOf(lines);
    }

    private static TerminalLine center(String line, int width) {
        int lineWidth = AnsiWidth.displayWidth(line);
        String centered = lineWidth >= width
            ? line
            : " ".repeat((width - lineWidth) / 2) + line;
        return new TerminalLine(AnsiWidth.truncate(centered, width));
    }
}
