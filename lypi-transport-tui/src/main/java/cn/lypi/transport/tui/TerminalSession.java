package cn.lypi.transport.tui;

import java.io.IOException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

public final class TerminalSession implements AutoCloseable {
    static final String SAVE_CURSOR = "\0337";
    static final String RESTORE_CURSOR = "\0338";
    static final String RESET_SCROLL_REGION = "\033[r";
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
            restoreAfterOpenFailure(io, interruptHandler, resizeHandler, rawMode);
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
            io.write(SAVE_CURSOR);
            io.write(RESET_SCROLL_REGION);
            io.write(DISABLE_MODIFY_OTHER_KEYS);
            io.write(DISABLE_BRACKETED_PASTE);
            io.write(RESTORE_CURSOR);
            io.write(SHOW_CURSOR);
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
        TerminalIo io,
        AutoCloseable interruptHandler,
        AutoCloseable resizeHandler,
        AutoCloseable rawMode
    ) {
        writeStaticQuietly(io, SAVE_CURSOR);
        writeStaticQuietly(io, RESET_SCROLL_REGION);
        writeStaticQuietly(io, DISABLE_MODIFY_OTHER_KEYS);
        writeStaticQuietly(io, DISABLE_BRACKETED_PASTE);
        writeStaticQuietly(io, RESTORE_CURSOR);
        writeStaticQuietly(io, SHOW_CURSOR);
        flushStaticQuietly(io);
        closeStaticQuietly(interruptHandler);
        closeStaticQuietly(resizeHandler);
        closeStaticQuietly(rawMode);
    }

    private static void writeStaticQuietly(TerminalIo io, String value) {
        try {
            io.write(value);
        } catch (IOException | RuntimeException ignored) {
            // NOTE: 打开失败时每个恢复序列都独立尝试，避免一次写失败阻断终端恢复。
        }
    }

    private static void flushStaticQuietly(TerminalIo io) {
        try {
            io.flush();
        } catch (IOException | RuntimeException ignored) {
            // NOTE: 打开失败回滚不能覆盖原始异常。
        }
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
