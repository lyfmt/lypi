package cn.lypi.ai.spec;

import java.util.Map;

public sealed interface LypiContentBlock permits
    LypiTextBlock,
    LypiThinkingBlock,
    LypiToolCallBlock,
    LypiToolResultBlock,
    LypiAttachmentBlock,
    LypiErrorBlock {
    String text();

    Map<String, Object> metadata();
}
