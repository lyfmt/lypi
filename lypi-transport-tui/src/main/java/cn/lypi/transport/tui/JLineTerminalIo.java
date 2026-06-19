package cn.lypi.transport.tui;

import java.io.IOException;
import java.io.IOError;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;

final class JLineTerminalIo implements TerminalIo {
    private static final int FALLBACK_WIDTH = 80;
    private static final int FALLBACK_HEIGHT = 24;

    private final Terminal terminal;

    JLineTerminalIo(Terminal terminal) {
        this.terminal = terminal;
    }

    @Override
    public AutoCloseable enterRawMode() {
        Attributes previous = terminal.enterRawMode();
        return () -> terminal.setAttributes(previous);
    }

    @Override
    public void write(String value) {
        terminal.writer().write(value);
    }

    @Override
    public void flush() {
        terminal.writer().flush();
    }

    @Override
    public int width() {
        try {
            return Math.max(1, terminal.getWidth());
        } catch (IOError | RuntimeException error) {
            return FALLBACK_WIDTH;
        }
    }

    @Override
    public int height() {
        try {
            return Math.max(1, terminal.getHeight());
        } catch (IOError | RuntimeException error) {
            return FALLBACK_HEIGHT;
        }
    }

    @Override
    public AutoCloseable onResize(Runnable callback) throws IOException {
        Terminal.SignalHandler previous = terminal.handle(Terminal.Signal.WINCH, signal -> callback.run());
        return () -> terminal.handle(Terminal.Signal.WINCH, previous);
    }

    @Override
    public AutoCloseable onInterrupt(Runnable callback) {
        Terminal.SignalHandler previous = terminal.handle(Terminal.Signal.INT, signal -> callback.run());
        return () -> terminal.handle(Terminal.Signal.INT, previous);
    }
}
