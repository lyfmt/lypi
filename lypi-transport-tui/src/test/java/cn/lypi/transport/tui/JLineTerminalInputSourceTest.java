package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.util.Optional;
import org.jline.terminal.Terminal;
import org.jline.utils.NonBlockingReader;
import org.junit.jupiter.api.Test;

class JLineTerminalInputSourceTest {
    @Test
    void readUsesBoundedPollTimeoutForFirstCharacter() throws IOException {
        RecordingReader reader = new RecordingReader(NonBlockingReader.READ_EXPIRED);
        JLineTerminalInputSource source = new JLineTerminalInputSource(reader);

        Optional<String> chunk = source.read();

        assertTrue(chunk.isEmpty());
        assertEquals(10L, reader.lastTimeout);
    }

    @Test
    void readReplaysCursorProbeInputBeforePollingTerminal() throws IOException {
        RecordingReader reader = new RecordingReader('x');
        JLineTerminalInputSource source = new JLineTerminalInputSource(terminal(reader), "typed");

        assertEquals(Optional.of("typed"), source.read());
        assertEquals(Optional.of("x"), source.read());
    }

    private static Terminal terminal(NonBlockingReader reader) {
        PrintWriter writer = new PrintWriter(new StringWriter());
        return (Terminal) Proxy.newProxyInstance(
            Terminal.class.getClassLoader(),
            new Class<?>[] { Terminal.class },
            (proxy, method, arguments) -> switch (method.getName()) {
                case "reader" -> reader;
                case "writer" -> writer;
                case "toString" -> "input-source-terminal";
                default -> throw new UnsupportedOperationException(method.getName());
            }
        );
    }

    private static final class RecordingReader extends NonBlockingReader {
        private final int result;
        private long lastTimeout = -1L;

        private RecordingReader(int result) {
            this.result = result;
        }

        @Override
        protected int read(long timeout, boolean isPeek) {
            lastTimeout = timeout;
            return result;
        }

        @Override
        public int readBuffered(char[] buffer, int offset, int length, long timeout) {
            return 0;
        }
    }
}
