package cn.lypi.transport.tui;

import java.io.IOException;
import java.util.Optional;

final class TerminalInputPump {
    private final TerminalInputSource inputSource;
    private final KeyMapper keyMapper;
    private final TuiInputLoop inputLoop;
    private final TerminalInputBuffer inputBuffer = new TerminalInputBuffer();

    TerminalInputPump(TerminalInputSource inputSource, KeyMapper keyMapper, TuiInputLoop inputLoop) {
        this.inputSource = inputSource;
        this.keyMapper = keyMapper;
        this.inputLoop = inputLoop;
    }

    /**
     * 排空当前可读终端输入，并分发到 TUI 输入循环。
     */
    void drainAvailable() throws IOException {
        drainAvailable(Integer.MAX_VALUE);
    }

    /**
     * 读取有限数量的终端输入片段，避免调用方长期持有 UI 锁。
     */
    void drainAvailable(int maxChunks) throws IOException {
        if (maxChunks <= 0) {
            return;
        }
        for (int drained = 0; drained < maxChunks; drained++) {
            Optional<String> chunk = inputSource.read();
            if (chunk.isEmpty()) {
                return;
            }
            dispatch(chunk.orElseThrow());
        }
    }

    Optional<String> readChunk() throws IOException {
        return inputSource.read();
    }

    void dispatchChunk(String chunk) {
        dispatch(chunk);
    }

    private void dispatch(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        for (TerminalInputSegment segment : inputBuffer.accept(chunk)) {
            dispatchSegment(segment);
        }
    }

    private void dispatchSegment(TerminalInputSegment segment) {
        switch (segment.kind()) {
            case TEXT -> inputLoop.acceptText(segment.value());
            case PASTE -> inputLoop.acceptPaste(segment.value());
            case KEY_SEQUENCE -> keyMapper.map(segment.value()).ifPresent(inputLoop::acceptKey);
        }
    }
}
