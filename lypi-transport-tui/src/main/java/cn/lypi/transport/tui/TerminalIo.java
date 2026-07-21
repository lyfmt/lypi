package cn.lypi.transport.tui;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;

interface TerminalIo {
    /**
     * 进入 raw mode，并返回用于恢复原终端属性的句柄。
     */
    AutoCloseable enterRawMode() throws IOException;

    /**
     * 写入终端控制序列或渲染内容。
     */
    void write(String value) throws IOException;

    /**
     * 刷新终端输出。
     */
    void flush() throws IOException;

    /**
     * 返回当前终端宽度。
     */
    int width();

    /**
     * 返回当前终端高度。
     */
    int height();

    /**
     * 有界查询 resize 后的硬件 cursor，并返回查询期间读到的普通输入。
     */
    default CursorProbeResult queryCursor(Duration timeout) throws IOException {
        return new CursorProbeResult(Optional.empty(), "");
    }

    /**
     * 注册 resize 回调，并返回用于恢复原信号处理器的句柄。
     */
    AutoCloseable onResize(Runnable callback) throws IOException;

    /**
     * 注册 Ctrl+C 中断回调，并返回用于恢复原信号处理器的句柄。
     */
    AutoCloseable onInterrupt(Runnable callback) throws IOException;
}
