package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class JLineTuiTransportConcurrencyTest {
    @Test
    void eventInputAndResizeRenderPathsShareUiMonitor() {
        StringBuilder order = new StringBuilder();
        JLineTuiTransport transport = new JLineTuiTransport(() -> order.append("render;"));

        transport.renderUnderUiLock();
        transport.runInputMutationForTest(() -> order.append("input;"));
        transport.runResizeMutationForTest(() -> order.append("resize;"));

        assertEquals("render;input;resize;", order.toString());
        assertEquals(3, transport.uiLockEntryCountForTest());
    }

    @Test
    void inputLoopCanRunThroughTransportUiMonitor() {
        StringBuilder order = new StringBuilder();
        JLineTuiTransport transport = new JLineTuiTransport(() -> {
        });

        transport.runInputMutationForTest(() -> order.append("key;"));

        assertEquals("key;", order.toString());
        assertEquals(1, transport.uiLockEntryCountForTest());
    }

    @Test
    void drainsTerminalInputThroughTransportUiMonitor() throws Exception {
        RecordingSubmitHandler submit = new RecordingSubmitHandler();
        JLineTuiTransport transport = JLineTuiTransport.withInput(
            ignored -> {
            },
            20,
            4,
            new QueueInputSource("hello", "\r"),
            submit
        );

        transport.drainInputForTest();

        assertEquals(List.of("hello"), submit.submitted);
        assertEquals(1, transport.uiLockEntryCountForTest());
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
