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
            "description", "本次等待的最长毫秒数；completion、用户输入或中断会提前结束，默认 600000。"
        );
    }

    static Map<String, Object> thinkingLevelSchema() {
        return Map.of(
            "type", "string",
            "enum", THINKING_LEVEL_VALUES,
            "description", "可选推理强度覆盖；省略或空白时继承当前 Agent 的 thinking level，建议直接省略；显式值由运行时校验。"
        );
    }

    static Map<String, Object> providerSchema() {
        return optionalModelOverrideSchema("provider");
    }

    static Map<String, Object> modelSchema() {
        return optionalModelOverrideSchema("model");
    }

    private static Map<String, Object> optionalModelOverrideSchema(String field) {
        return Map.of(
            "type", "string",
            "description", "可选 %s 覆盖；省略或空白时继承当前 Agent 的 %s，建议直接省略；显式值由运行时校验。"
                .formatted(field, field)
        );
    }
}
