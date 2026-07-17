package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Optional;
import org.jline.terminal.Terminal;
import org.jline.utils.NonBlockingReader;
import org.junit.jupiter.api.Test;

class TerminalCursorProbeTest {
    @Test
    void parseExtractsFirstCursorPositionAndPreservesOtherInput() {
        assertEquals(
            new CursorProbeResult(Optional.of(new TerminalPosition(11, 6)), "typed"),
            TerminalCursorProbe.parse("typed\033[7;12R")
        );
        assertEquals(
            new CursorProbeResult(Optional.empty(), "typed"),
            TerminalCursorProbe.parse("typed")
        );
    }

    @Test
    void queryReturnsWithinDeadlineWhenTerminalDoesNotRespond() throws Exception {
        StringWriter output = new StringWriter();
        Terminal terminal = terminal(new ExpiringReader(), output);

        long started = System.nanoTime();
        CursorProbeResult result = TerminalCursorProbe.query(terminal, Duration.ofMillis(25));
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertTrue(result.position().isEmpty());
        assertEquals("", result.replayInput());
        assertEquals("\033[6n", output.toString());
        assertTrue(elapsedMillis < 250, () -> "probe took " + elapsedMillis + "ms");
    }

    @Test
    void queryReturnsCursorPositionAndInputReadBeforeResponse() throws Exception {
        StringWriter output = new StringWriter();
        Terminal terminal = terminal(new SequenceReader("typed\033[7;12R"), output);

        CursorProbeResult result = TerminalCursorProbe.query(terminal, Duration.ofMillis(25));

        assertEquals(
            new CursorProbeResult(Optional.of(new TerminalPosition(11, 6)), "typed"),
            result
        );
        assertEquals("\033[6n", output.toString());
    }

    private static Terminal terminal(NonBlockingReader reader, StringWriter output) {
        PrintWriter writer = new PrintWriter(output);
        return (Terminal) Proxy.newProxyInstance(
            Terminal.class.getClassLoader(),
            new Class<?>[] { Terminal.class },
            (proxy, method, arguments) -> switch (method.getName()) {
                case "reader" -> reader;
                case "writer" -> writer;
                case "flush" -> {
                    writer.flush();
                    yield null;
                }
                case "toString" -> "probe-terminal";
                default -> throw new UnsupportedOperationException(method.getName());
            }
        );
    }

    private static final class ExpiringReader extends NonBlockingReader {
        @Override
        protected int read(long timeout, boolean isPeek) {
            return READ_EXPIRED;
        }

        @Override
        public int readBuffered(char[] buffer, int offset, int length, long timeout) {
            return 0;
        }
    }

    private static final class SequenceReader extends NonBlockingReader {
        private final String input;
        private int index;

        private SequenceReader(String input) {
            this.input = input;
        }

        @Override
        protected int read(long timeout, boolean isPeek) {
            if (index >= input.length()) {
                return READ_EXPIRED;
            }
            int next = input.charAt(index);
            if (!isPeek) {
                index++;
            }
            return next;
        }

        @Override
        public int readBuffered(char[] buffer, int offset, int length, long timeout) {
            return 0;
        }
    }
}
