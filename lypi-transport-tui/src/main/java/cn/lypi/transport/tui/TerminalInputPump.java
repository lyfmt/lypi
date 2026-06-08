package cn.lypi.transport.tui;

import java.io.IOException;
import java.util.Optional;

final class TerminalInputPump {
    private final TerminalInputSource inputSource;
    private final KeyMapper keyMapper;
    private final TuiInputLoop inputLoop;

    TerminalInputPump(TerminalInputSource inputSource, KeyMapper keyMapper, TuiInputLoop inputLoop) {
        this.inputSource = inputSource;
        this.keyMapper = keyMapper;
        this.inputLoop = inputLoop;
    }

    /**
     * 排空当前可读终端输入，并分发到 TUI 输入循环。
     */
    void drainAvailable() throws IOException {
        Optional<String> chunk = inputSource.read();
        while (chunk.isPresent()) {
            dispatch(chunk.orElseThrow());
            chunk = inputSource.read();
        }
    }

    private void dispatch(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        if (isPlainText(chunk)) {
            inputLoop.acceptText(chunk);
            return;
        }
        keyMapper.map(chunk).ifPresent(inputLoop::acceptKey);
    }

    private boolean isPlainText(String chunk) {
        return chunk.codePoints().allMatch(codePoint -> codePoint >= 0x20 && codePoint != 0x7F);
    }
}
