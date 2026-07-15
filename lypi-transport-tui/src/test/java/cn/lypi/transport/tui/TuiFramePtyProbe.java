package cn.lypi.transport.tui;

import cn.lypi.contracts.tui.StatusBarState;
import cn.lypi.contracts.tui.TuiMessageBlock;
import cn.lypi.contracts.tui.TuiToolBlock;
import cn.lypi.contracts.tui.TuiToolState;
import cn.lypi.contracts.tui.TuiViewModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

public final class TuiFramePtyProbe {
    private TuiFramePtyProbe() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("expected ready and exit file paths");
        }
        Path readyFile = Path.of(args[0]);
        Path exitFile = Path.of(args[1]);
        Terminal terminal = TerminalBuilder.builder().system(true).build();
        TerminalIo io = new JLineTerminalIo(terminal);
        try (TerminalSession session = TerminalSession.open(io)) {
            TerminalFrameRenderer frameRenderer = new TerminalFrameRenderer(io);
            TuiRenderer renderer = new TuiRenderer();
            TuiScreen screen = new TuiScreen(Math.max(1, io.height() - 4));
            TuiLayout layout = new TuiLayout(io.width(), io.height());

            frameRenderer.render(renderer.renderFrame(view("status-old"), screen, layout, "", 0));
            frameRenderer.render(renderer.renderFrame(view("status-updated"), screen, layout, "input", 5));
            Files.writeString(readyFile, "ready");
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
            while (!Files.exists(exitFile)) {
                if (System.nanoTime() >= deadline) {
                    throw new IllegalStateException("timed out waiting for PTY exit signal");
                }
                Thread.sleep(25);
            }
        }
    }

    private static TuiViewModel view(String runtimeLine) {
        return new TuiViewModel(
            List.of(
                new TuiMessageBlock("history:pty", "message:history", "assistant", "history stable", false),
                new TuiToolBlock(
                    "tool:pty",
                    "message:pty",
                    "tool-use:pty",
                    "bash",
                    TuiToolState.RUNNING,
                    "tool one\ntool two",
                    "",
                    true
                )
            ),
            new StatusBarState("status", "", "", ""),
            runtimeLine,
            List.of(),
            Optional.empty(),
            Optional.empty()
        );
    }
}
