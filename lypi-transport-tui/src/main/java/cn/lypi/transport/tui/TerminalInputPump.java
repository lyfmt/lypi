package cn.lypi.transport.tui;

import java.io.IOException;
import java.util.Optional;

final class TerminalInputPump {
    private static final String BRACKETED_PASTE_START = "\033[200~";
    private static final String BRACKETED_PASTE_END = "\033[201~";

    private final TerminalInputSource inputSource;
    private final KeyMapper keyMapper;
    private final TuiInputLoop inputLoop;
    private StringBuilder pendingPaste;

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

    private void dispatch(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        if (pendingPaste != null) {
            appendPasteChunk(chunk);
            return;
        }
        if (isBracketedPaste(chunk)) {
            inputLoop.acceptPaste(chunk.substring(
                BRACKETED_PASTE_START.length(),
                chunk.length() - BRACKETED_PASTE_END.length()
            ));
            return;
        }
        if (chunk.startsWith(BRACKETED_PASTE_START)) {
            pendingPaste = new StringBuilder();
            appendPasteChunk(chunk.substring(BRACKETED_PASTE_START.length()));
            return;
        }
        if (isPlainText(chunk)) {
            inputLoop.acceptText(chunk);
            return;
        }
        keyMapper.map(chunk).ifPresent(inputLoop::acceptKey);
    }

    private void appendPasteChunk(String chunk) {
        int end = chunk.indexOf(BRACKETED_PASTE_END);
        if (end >= 0) {
            pendingPaste.append(chunk, 0, end);
            inputLoop.acceptPaste(pendingPaste.toString());
            pendingPaste = null;
            String remaining = chunk.substring(end + BRACKETED_PASTE_END.length());
            dispatch(remaining);
            return;
        }
        pendingPaste.append(chunk);
    }

    private boolean isBracketedPaste(String chunk) {
        return chunk.startsWith(BRACKETED_PASTE_START) && chunk.endsWith(BRACKETED_PASTE_END);
    }

    private boolean isPlainText(String chunk) {
        return chunk.codePoints().allMatch(codePoint -> codePoint >= 0x20 && codePoint != 0x7F);
    }
}
