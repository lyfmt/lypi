package cn.lypi.transport.tui;

import java.io.IOException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

public final class TerminalSession implements AutoCloseable {
    static final String ENABLE_BRACKETED_PASTE = "\033[?2004h";
    static final String DISABLE_BRACKETED_PASTE = "\033[?2004l";
    static final String HIDE_CURSOR = "\033[?25l";
    static final String SHOW_CURSOR = "\033[?25h";
    static final String ENABLE_MODIFY_OTHER_KEYS = "\033[>4;2m";
    static final String DISABLE_MODIFY_OTHER_KEYS = "\033[>4m";

    private final TerminalIo io;
    private final AutoCloseable rawMode;
    private final AutoCloseable resizeHandler;
    private final AutoCloseable interruptHandler;
    private boolean closed;
    private int renderedRows;

    private TerminalSession(TerminalIo io, AutoCloseable rawMode, AutoCloseable resizeHandler, AutoCloseable interruptHandler) {
        this.io = io;
        this.rawMode = rawMode;
        this.resizeHandler = resizeHandler;
        this.interruptHandler = interruptHandler;
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
        return open(io, resizeCallback, () -> {
        });
    }

    static TerminalSession open(TerminalIo io, Runnable resizeCallback, Runnable interruptCallback) throws IOException {
        AutoCloseable rawMode = null;
        AutoCloseable resizeHandler = null;
        AutoCloseable interruptHandler = null;
        try {
            rawMode = io.enterRawMode();
            resizeHandler = io.onResize(resizeCallback);
            interruptHandler = io.onInterrupt(interruptCallback);
            io.write(ENABLE_BRACKETED_PASTE);
            io.write(HIDE_CURSOR);
            io.write(ENABLE_MODIFY_OTHER_KEYS);
            io.flush();
            return new TerminalSession(io, rawMode, resizeHandler, interruptHandler);
        } catch (IOException | RuntimeException exception) {
            restoreAfterOpenFailure(interruptHandler, resizeHandler, rawMode);
            throw exception;
        }
    }

    void updateRenderedRows(int renderedRows) {
        this.renderedRows = Math.max(0, renderedRows);
    }

    @Override
    public void close() throws Exception {
        if (closed) {
            return;
        }
        closed = true;
        try {
            io.write(DISABLE_MODIFY_OTHER_KEYS);
            io.write(SHOW_CURSOR);
            io.write(DISABLE_BRACKETED_PASTE);
            if (renderedRows > 0) {
                io.write("\033[" + renderedRows + ";1H");
            }
            io.write("\n");
            io.flush();
        } finally {
            closeQuietly(interruptHandler);
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

    private static void restoreAfterOpenFailure(
        AutoCloseable interruptHandler,
        AutoCloseable resizeHandler,
        AutoCloseable rawMode
    ) {
        closeStaticQuietly(interruptHandler);
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
