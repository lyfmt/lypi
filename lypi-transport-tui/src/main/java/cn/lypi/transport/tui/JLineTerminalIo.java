package cn.lypi.transport.tui;

import java.io.IOException;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;

final class JLineTerminalIo implements TerminalIo {
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
        return terminal.getWidth();
    }

    @Override
    public int height() {
        return terminal.getHeight();
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
