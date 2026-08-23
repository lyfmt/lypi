package cn.lycode.ai.spec;

import java.util.Map;

public sealed interface LyCodeContentBlock permits
    LyCodeTextBlock,
    LyCodeThinkingBlock,
    LyCodeToolCallBlock,
    LyCodeToolResultBlock,
    LyCodeAttachmentBlock,
    LyCodeErrorBlock {
    String text();

    Map<String, Object> metadata();
}
