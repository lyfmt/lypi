package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.lypi.contracts.tui.PermissionPromptView;
import cn.lypi.contracts.tui.StatusBarState;
import cn.lypi.contracts.tui.TuiViewModel;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TerminalInputPumpTest {
    @Test
    void dispatchesTextAndMappedKeySequencesToInputLoop() throws IOException {
        RecordingSubmitHandler submit = new RecordingSubmitHandler();
        TuiInputLoop loop = new TuiInputLoop(
            submit,
            () -> {
            },
            new TuiLayout(20, 4)
        );
        TerminalInputPump pump = new TerminalInputPump(
            new QueueInputSource("hello", "\033[13;5u", "world", "\r"),
            new KeyMapper(),
            loop
        );

        pump.drainAvailable();

        assertEquals(List.of("hello\nworld"), submit.submitted);
        assertEquals("", loop.draft());
    }

    @Test
    void handlesSplitModifiedEnterSequenceAcrossRawInputChunks() throws IOException {
        RecordingSubmitHandler submit = new RecordingSubmitHandler();
        TuiInputLoop loop = new TuiInputLoop(
            submit,
            () -> {
            },
            new TuiLayout(40, 4)
        );
        TerminalInputPump pump = new TerminalInputPump(
            new QueueInputSource("hello", "\033[13", ";5u", "world", "\r"),
            new KeyMapper(),
            loop
        );

        pump.drainAvailable();

        assertEquals(List.of("hello\nworld"), submit.submitted);
    }

    @Test
    void dispatchesStandaloneEscapeToPermissionPromptInterrupt() throws IOException {
        RecordingSubmitHandler submit = new RecordingSubmitHandler();
        TuiInputLoop loop = new TuiInputLoop(
            submit,
            () -> {
            },
            new TuiLayout(40, 4),
            TerminalInputPumpTest::permissionView
        );
        TerminalInputPump pump = new TerminalInputPump(
            new QueueInputSource("\033"),
            new KeyMapper(),
            loop
        );

        pump.drainAvailable();

        assertEquals(List.of(), submit.permissionOptions);
        assertEquals(List.of("esc"), submit.interruptReasons);
    }

    @Test
    void dispatchesBracketedPasteAsAtomicPaste() throws IOException {
        RecordingSubmitHandler submit = new RecordingSubmitHandler();
        TuiInputLoop loop = new TuiInputLoop(
            submit,
            () -> {
            },
            new TuiLayout(40, 4)
        );
        TerminalInputPump pump = new TerminalInputPump(
            new QueueInputSource("\033[200~" + "a".repeat(128) + "\033[201~", "\r"),
            new KeyMapper(),
            loop
        );

        pump.drainAvailable();

        assertEquals(List.of("a".repeat(128)), submit.submitted);
    }

    @Test
    void assemblesBracketedPasteAcrossRawInputChunks() throws IOException {
        RecordingSubmitHandler submit = new RecordingSubmitHandler();
        TuiInputLoop loop = new TuiInputLoop(
            submit,
            () -> {
            },
            new TuiLayout(40, 4)
        );
        TerminalInputPump pump = new TerminalInputPump(
            new QueueInputSource("\033[200~", "alpha\n", "beta", "\033[201~", "\r"),
            new KeyMapper(),
            loop
        );

        pump.drainAvailable();

        assertEquals(List.of("alpha\nbeta"), submit.submitted);
    }

    @Test
    void dispatchesTextPasteAndRemainingKeyFromOneRawChunk() throws IOException {
        RecordingSubmitHandler submit = new RecordingSubmitHandler();
        TuiInputLoop loop = new TuiInputLoop(
            submit,
            () -> {
            },
            new TuiLayout(40, 4)
        );
        TerminalInputPump pump = new TerminalInputPump(
            new QueueInputSource("hi\033[200~alpha\nbeta\033[201~\r"),
            new KeyMapper(),
            loop
        );

        pump.drainAvailable();

        assertEquals(List.of("hialpha\nbeta"), submit.submitted);
    }

    @Test
    void dispatchesInputRemainingAfterPasteEndMarker() throws IOException {
        RecordingSubmitHandler submit = new RecordingSubmitHandler();
        TuiInputLoop loop = new TuiInputLoop(
            submit,
            () -> {
            },
            new TuiLayout(40, 4)
        );
        TerminalInputPump pump = new TerminalInputPump(
            new QueueInputSource("\033[200~", "alpha\033[201~\r"),
            new KeyMapper(),
            loop
        );

        pump.drainAvailable();

        assertEquals(List.of("alpha"), submit.submitted);
    }

    @Test
    void flushesIncompleteBufferedSequenceWhenInputIsDrained() throws IOException {
        RecordingSubmitHandler submit = new RecordingSubmitHandler();
        TuiInputLoop loop = new TuiInputLoop(
            submit,
            () -> {
            },
            new TuiLayout(40, 4)
        );
        TerminalInputPump pump = new TerminalInputPump(
            new QueueInputSource("\033[<35"),
            new KeyMapper(),
            loop
        );

        pump.drainAvailable();
        pump.dispatchChunk("tail\r");

        assertEquals(List.of("tail"), submit.submitted);
    }

    @Test
    void nativeScrollbackSequencesAreIgnoredWithoutChangingDraft() throws IOException {
        TuiInputLoop loop = new TuiInputLoop(
            new RecordingSubmitHandler(),
            () -> {
            },
            new TuiLayout(40, 4)
        );
        loop.acceptText("draft");
        TerminalInputPump pump = new TerminalInputPump(
            new QueueInputSource(
                "\033[5~",
                "\033[6~",
                "\033[<64;40;12M",
                "\033[<65;40;12M"
            ),
            new KeyMapper(),
            loop
        );

        pump.drainAvailable();

        assertEquals("draft", loop.draft());
        assertEquals(5, loop.cursor());
    }

    @Test
    void keepsPendingPasteWhenInputIsTemporarilyDrained() throws IOException {
        RecordingSubmitHandler submit = new RecordingSubmitHandler();
        TuiInputLoop loop = new TuiInputLoop(
            submit,
            () -> {
            },
            new TuiLayout(40, 4)
        );
        TerminalInputPump pump = new TerminalInputPump(
            new QueueInputSource("\033[200~alpha\n"),
            new KeyMapper(),
            loop
        );

        pump.drainAvailable();
        pump.dispatchChunk("beta\033[201~\r");

        assertEquals(List.of("alpha\nbeta"), submit.submitted);
    }

    private static final class QueueInputSource implements TerminalInputSource {
        private final ArrayDeque<String> chunks;

        private QueueInputSource(String... chunks) {
            this.chunks = new ArrayDeque<>(List.of(chunks));
        }

        @Override
        public Optional<String> read() {
            return Optional.ofNullable(chunks.pollFirst());
        }
    }

    private static final class RecordingSubmitHandler implements TuiSubmitHandler {
        private final List<String> submitted = new ArrayList<>();
        private final List<String> permissionOptions = new ArrayList<>();
        private final List<String> interruptReasons = new ArrayList<>();

        @Override
        public void submitUserInput(String input) {
            submitted.add(input);
        }

        @Override
        public void requestInterrupt(String reason) {
            interruptReasons.add(reason);
        }

        @Override
        public void submitPermissionOption(String requestId, String toolUseId, String optionId) {
            permissionOptions.add(requestId + ":" + toolUseId + ":" + optionId);
        }
    }

    private static TuiViewModel permissionView() {
        return new TuiViewModel(
            List.of(),
            new StatusBarState("ses_1", "gpt-5.4", "execute", "default"),
            List.of(),
            Optional.of(new PermissionPromptView("perm_toolu_1", "toolu_1", "Need approval", "bash:npm test", "allow_once", "cancel")),
            Optional.empty()
        );
    }
}
