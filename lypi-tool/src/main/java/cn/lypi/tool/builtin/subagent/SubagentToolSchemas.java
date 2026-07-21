package cn.lypi.tool.builtin.subagent;

import cn.lypi.contracts.model.ThinkingLevel;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

final class SubagentToolSchemas {
    static final long DEFAULT_TIMEOUT_MILLIS = 600_000L;
    static final long MAX_TIMEOUT_MILLIS = 3_600_000L;
    static final List<String> THINKING_LEVEL_VALUES = Arrays.stream(ThinkingLevel.values())
        .map(Enum::name)
        .toList();

    private SubagentToolSchemas() {
    }

    static Map<String, Object> timeoutMillisSchema() {
        return Map.of(
            "type", "integer",
            "minimum", 0L,
            "maximum", MAX_TIMEOUT_MILLIS,
            "description", "等待任意 subagent mailbox 消息的最长毫秒数，默认 600000。"
        );
    }

    static Map<String, Object> thinkingLevelSchema() {
        return Map.of(
            "type", "string",
            "enum", THINKING_LEVEL_VALUES,
            "description", "推理强度；省略时继承主 Agent，显式值由运行时校验。"
        );
    }
}
