package cn.lypi.transport.tui;

import java.io.IOException;
import java.util.Optional;

interface TerminalInputSource {
    /**
     * 读取下一段终端原始输入。
     */
    Optional<String> read() throws IOException;
}
