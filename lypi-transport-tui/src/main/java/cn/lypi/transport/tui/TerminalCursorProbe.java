package cn.lypi.transport.tui;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jline.terminal.Terminal;
import org.jline.utils.NonBlockingReader;

final class TerminalCursorProbe {
    private static final Pattern CURSOR_POSITION_REPORT = Pattern.compile("\033\\[([1-9]\\d*);([1-9]\\d*)R");

    private TerminalCursorProbe() {
    }

    static CursorProbeResult query(Terminal terminal, Duration timeout) throws IOException {
        Objects.requireNonNull(terminal, "terminal");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("cursor probe timeout must be non-negative");
        }

        terminal.writer().write("\033[6n");
        terminal.flush();

        long deadline = System.nanoTime() + timeout.toNanos();
        StringBuilder response = new StringBuilder();
        while (true) {
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                break;
            }
            long remainingMillis = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
            int next = terminal.reader().read(remainingMillis);
            if (next == NonBlockingReader.EOF) {
                break;
            }
            if (next == NonBlockingReader.READ_EXPIRED) {
                continue;
            }
            response.append((char) next);
            CursorProbeResult parsed = parse(response.toString());
            if (parsed.position().isPresent()) {
                return parsed;
            }
        }
        return parse(response.toString());
    }

    static CursorProbeResult parse(String input) {
        String value = input == null ? "" : input;
        Matcher matcher = CURSOR_POSITION_REPORT.matcher(value);
        if (!matcher.find()) {
            return new CursorProbeResult(Optional.empty(), value);
        }
        int row = Integer.parseInt(matcher.group(1)) - 1;
        int column = Integer.parseInt(matcher.group(2)) - 1;
        String replayInput = value.substring(0, matcher.start()) + value.substring(matcher.end());
        return new CursorProbeResult(Optional.of(new TerminalPosition(column, row)), replayInput);
    }
}

record CursorProbeResult(Optional<TerminalPosition> position, String replayInput) {
    CursorProbeResult {
        position = position == null ? Optional.empty() : position;
        replayInput = replayInput == null ? "" : replayInput;
    }
}
