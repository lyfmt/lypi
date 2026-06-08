package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
            ignored -> {
            },
            new TuiRenderer(),
            new TuiScreen(2),
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

        @Override
        public void submitUserInput(String input) {
            submitted.add(input);
        }

        @Override
        public void requestInterrupt(String reason) {
        }
    }
}
