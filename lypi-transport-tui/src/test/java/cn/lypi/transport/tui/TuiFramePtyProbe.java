package cn.lypi.transport.tui;

import cn.lypi.contracts.tui.StatusBarState;
import cn.lypi.contracts.tui.TuiMessageBlock;
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
        if (args.length != 4) {
            throw new IllegalArgumentException("expected ready, replace, replaced, and exit file paths");
        }
        Path readyFile = Path.of(args[0]);
        Path replaceFile = Path.of(args[1]);
        Path replacedFile = Path.of(args[2]);
        Path exitFile = Path.of(args[3]);
        System.out.print("SHELL_SENTINEL\n");
        System.out.flush();
        Terminal terminal = TerminalBuilder.builder().system(true).build();
        TerminalIo io = new JLineTerminalIo(terminal);
        try (TerminalSession session = TerminalSession.open(io)) {
            InlineTerminalRenderer terminalRenderer = InlineTerminalRenderer.withStartupBanner(
                io,
                new InlineViewport(Math.max(0, io.height() - 1), 1, io.width(), io.height())
            );
            TuiRenderer renderer = new TuiRenderer();
            TuiTranscriptPartitioner partitioner = new TuiTranscriptPartitioner();
            TuiTranscriptCommitLedger ledger = new TuiTranscriptCommitLedger();
            TuiLayout layout = new TuiLayout(io.width(), io.height());

            try {
                render(
                    terminalRenderer,
                    renderer,
                    partitioner,
                    ledger,
                    layout,
                    view("status-old"),
                    "",
                    0,
                    TuiRenderIntent.UPDATE
                );
                render(
                    terminalRenderer,
                    renderer,
                    partitioner,
                    ledger,
                    layout,
                    view("status-updated"),
                    "input",
                    5,
                    TuiRenderIntent.UPDATE
                );
                Files.writeString(readyFile, "ready");
                waitForFile(replaceFile, "PTY replacement signal");
                ledger.reset();
                render(
                    terminalRenderer,
                    renderer,
                    partitioner,
                    ledger,
                    layout,
                    replacementView(),
                    "resumed",
                    7,
                    TuiRenderIntent.REPLACE_SESSION
                );
                Files.writeString(replacedFile, "replaced");
                waitForFile(exitFile, "PTY exit signal");
            } finally {
                terminalRenderer.finish();
            }
        }
    }

    private static void render(
        InlineTerminalRenderer terminalRenderer,
        TuiRenderer renderer,
        TuiTranscriptPartitioner partitioner,
        TuiTranscriptCommitLedger ledger,
        TuiLayout layout,
        TuiViewModel view,
        String input,
        int cursor,
        TuiRenderIntent intent
    ) throws Exception {
        TuiTranscriptPartition partition = partitioner.partition(view.blocks());
        List<TerminalLine> history = renderer.renderCommittedBlocks(
            ledger.advance(new TuiProjectionKey("pty", "leaf"), partition.history()),
            layout.width()
        );
        TuiRenderFrame surface = renderer.renderSurface(
            view,
            partition.live(),
            layout,
            input,
            cursor,
            List.of(),
            false
        );
        terminalRenderer.render(new TuiRenderBatch(history, surface, intent));
    }

    private static TuiViewModel view(String runtimeLine) {
        return new TuiViewModel(
            List.of(
                new TuiMessageBlock("history:pty", "message:history", "assistant", "history stable", false),
                new TuiMessageBlock("live:pty", "message:live", "assistant", "stream/live row", true)
            ),
            new StatusBarState("", "", "", "", runtimeLine, "", "", false),
            "",
            List.of(),
            Optional.empty(),
            Optional.empty()
        );
    }

    private static TuiViewModel replacementView() {
        return new TuiViewModel(
            List.of(
                new TuiMessageBlock(
                    "history:replacement",
                    "message:replacement-history",
                    "assistant",
                    "replacement history",
                    false
                ),
                new TuiMessageBlock(
                    "live:replacement",
                    "message:replacement-live",
                    "assistant",
                    "replacement live",
                    true
                )
            ),
            new StatusBarState("", "", "", "", "replacement status", "", "", false),
            "",
            List.of(),
            Optional.empty(),
            Optional.empty()
        );
    }

    private static void waitForFile(Path path, String signalName) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
        while (!Files.exists(path)) {
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("timed out waiting for " + signalName);
            }
            Thread.sleep(25);
        }
    }
}
