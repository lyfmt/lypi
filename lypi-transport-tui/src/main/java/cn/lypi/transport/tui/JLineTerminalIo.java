package cn.lypi.transport.tui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.jline.terminal.Attributes;
import org.jline.terminal.Attributes.ControlChar;
import org.jline.terminal.Attributes.LocalFlag;
import org.jline.terminal.Terminal;

final class JLineTerminalIo implements TerminalIo {
    private final Terminal terminal;
    private final TerminalModeController terminalModeController;

    JLineTerminalIo(Terminal terminal) {
        this(terminal, new SttyTerminalModeController());
    }

    JLineTerminalIo(Terminal terminal, TerminalModeController terminalModeController) {
        this.terminal = terminal;
        this.terminalModeController = terminalModeController;
    }

    @Override
    public AutoCloseable enterRawMode() throws IOException {
        AutoCloseable sttyMode = terminalModeController.enterRawMode();
        Attributes previous = terminal.enterRawMode();
        Attributes interactive = new Attributes(terminal.getAttributes());
        interactive.setLocalFlag(LocalFlag.ECHO, false);
        interactive.setLocalFlag(LocalFlag.ECHOCTL, false);
        interactive.setControlChar(ControlChar.VDISCARD, -1);
        terminal.setAttributes(interactive);
        return () -> {
            try {
                terminal.setAttributes(previous);
            } finally {
                sttyMode.close();
            }
        };
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

    interface TerminalModeController {
        AutoCloseable enterRawMode() throws IOException;
    }

    private static final class SttyTerminalModeController implements TerminalModeController {
        @Override
        public AutoCloseable enterRawMode() throws IOException {
            String previous = runShell("stty -g < /dev/tty").trim();
            runShell("stty raw -echo -echoctl discard undef < /dev/tty");
            return () -> runShell("stty " + previous + " < /dev/tty");
        }

        private static String runShell(String command) throws IOException {
            try {
                Process process = new ProcessBuilder("sh", "-c", command)
                    .redirectErrorStream(true)
                    .start();
                byte[] output = process.getInputStream().readAllBytes();
                int exitCode = process.waitFor();
                String text = new String(output, StandardCharsets.UTF_8);
                if (exitCode != 0) {
                    throw new IOException("stty failed: " + text.trim());
                }
                return text;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("stty interrupted", exception);
            }
        }
    }
}
