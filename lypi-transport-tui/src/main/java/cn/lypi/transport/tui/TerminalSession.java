package cn.lypi.transport.tui;

import java.io.IOException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

public final class TerminalSession implements AutoCloseable {
    static final String ENTER_ALTERNATE_SCREEN = "\033[?1049h";
    static final String EXIT_ALTERNATE_SCREEN = "\033[?1049l";
    static final String ENABLE_BRACKETED_PASTE = "\033[?2004h";
    static final String DISABLE_BRACKETED_PASTE = "\033[?2004l";
    static final String HIDE_CURSOR = "\033[?25l";
    static final String SHOW_CURSOR = "\033[?25h";
    static final String ENABLE_KITTY_KEYBOARD = "\033[?u";
    static final String DISABLE_KITTY_KEYBOARD = "\033[?u";
    static final String ENABLE_MODIFY_OTHER_KEYS = "\033[>4;2m";
    static final String DISABLE_MODIFY_OTHER_KEYS = "\033[>4m";

    private final TerminalIo io;
    private final AutoCloseable rawMode;
    private final AutoCloseable resizeHandler;
    private boolean closed;

    private TerminalSession(TerminalIo io, AutoCloseable rawMode, AutoCloseable resizeHandler) {
        this.io = io;
        this.rawMode = rawMode;
        this.resizeHandler = resizeHandler;
    }

    /**
     * 使用系统终端打开一个 JLine TUI session。
     */
    public static TerminalSession open() throws IOException {
        Terminal terminal = TerminalBuilder.builder()
            .system(true)
            .build();
        return open(terminal, () -> {
        });
    }

    /**
     * 使用指定 JLine terminal 打开 TUI session。
     */
    public static TerminalSession open(Terminal terminal, Runnable resizeCallback) throws IOException {
        return open(new JLineTerminalIo(terminal), resizeCallback);
    }

    static TerminalSession open(TerminalIo io) throws IOException {
        return open(io, () -> {
        });
    }

    static TerminalSession open(TerminalIo io, Runnable resizeCallback) throws IOException {
        AutoCloseable rawMode = null;
        AutoCloseable resizeHandler = null;
        try {
            rawMode = io.enterRawMode();
            resizeHandler = io.onResize(resizeCallback);
            io.write(ENTER_ALTERNATE_SCREEN);
            io.write(ENABLE_BRACKETED_PASTE);
            io.write(HIDE_CURSOR);
            io.write(ENABLE_KITTY_KEYBOARD);
            io.write(ENABLE_MODIFY_OTHER_KEYS);
            io.flush();
            return new TerminalSession(io, rawMode, resizeHandler);
        } catch (IOException | RuntimeException exception) {
            restoreAfterOpenFailure(resizeHandler, rawMode);
            throw exception;
        }
    }

    @Override
    public void close() throws Exception {
        if (closed) {
            return;
        }
        closed = true;
        try {
            io.write(DISABLE_MODIFY_OTHER_KEYS);
            io.write(DISABLE_KITTY_KEYBOARD);
            io.write(SHOW_CURSOR);
            io.write(DISABLE_BRACKETED_PASTE);
            io.write(EXIT_ALTERNATE_SCREEN);
            io.flush();
        } finally {
            closeQuietly(resizeHandler);
            rawMode.close();
        }
    }

    private void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
            // NOTE: 关闭终端时优先恢复 raw mode，resize handler 恢复失败不应阻断。
        }
    }

    private static void restoreAfterOpenFailure(AutoCloseable resizeHandler, AutoCloseable rawMode) {
        closeStaticQuietly(resizeHandler);
        closeStaticQuietly(rawMode);
    }

    private static void closeStaticQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // NOTE: 打开失败回滚时尽量恢复已获取资源，失败不覆盖原始异常。
        }
    }
}
