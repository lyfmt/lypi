package cn.lypi.transport.tui;

import java.io.IOException;
import java.util.Optional;
import org.jline.terminal.Terminal;
import org.jline.utils.NonBlockingReader;

final class JLineTerminalInputSource implements TerminalInputSource {
    private static final long FIRST_CHARACTER_TIMEOUT_MILLIS = 10L;
    private static final long ESCAPE_CONTINUATION_TIMEOUT_MILLIS = 10L;

    private final NonBlockingReader reader;
    private String replayInput;

    JLineTerminalInputSource(Terminal terminal) {
        this(terminal, "");
    }

    JLineTerminalInputSource(Terminal terminal, String replayInput) {
        this(terminal.reader(), replayInput);
    }

    JLineTerminalInputSource(NonBlockingReader reader) {
        this(reader, "");
    }

    private JLineTerminalInputSource(NonBlockingReader reader, String replayInput) {
        this.reader = reader;
        this.replayInput = replayInput == null ? "" : replayInput;
    }

    @Override
    public Optional<String> read() throws IOException {
        synchronized (reader) {
            if (!replayInput.isEmpty()) {
                String replay = replayInput;
                replayInput = "";
                return Optional.of(replay);
            }
            int first = reader.read(FIRST_CHARACTER_TIMEOUT_MILLIS);
            if (first == NonBlockingReader.READ_EXPIRED || first == NonBlockingReader.EOF) {
                return Optional.empty();
            }
            StringBuilder chunk = new StringBuilder();
            chunk.append((char) first);
            if (first == '\033') {
                readEscapeContinuation(chunk);
            }
            return Optional.of(chunk.toString());
        }
    }

    private void readEscapeContinuation(StringBuilder chunk) throws IOException {
        int next = reader.read(ESCAPE_CONTINUATION_TIMEOUT_MILLIS);
        while (next != NonBlockingReader.READ_EXPIRED && next != NonBlockingReader.EOF) {
            chunk.append((char) next);
            next = reader.read(ESCAPE_CONTINUATION_TIMEOUT_MILLIS);
        }
    }
}
