package cn.lypi.tool.builtin.subagent;

import java.util.List;
import java.util.Map;

final class SubagentToolSchemas {
    static final int DEFAULT_TIMEOUT_SECONDS = 1_200;
    static final int MAX_TIMEOUT_SECONDS = 1_200;
    static final List<String> PERMISSION_MODE_VALUES = List.of("DEFAULT_EXECUTE", "ACCEPT_EDITS", "BYPASS");
    static final List<String> AGENT_MODE_VALUES = List.of("PLAN", "EXECUTE");

    private SubagentToolSchemas() {
    }

    static Map<String, Object> timeoutSecondsSchema() {
        return Map.of(
            "type", "integer",
            "minimum", 1,
            "maximum", MAX_TIMEOUT_SECONDS,
            "description", "子 Agent 单次运行/等待最长秒数。默认 1200 秒，最大 1200 秒（20 分钟）。"
        );
    }

    static Map<String, Object> permissionModeSchema() {
        return Map.of(
            "type", "string",
            "enum", PERMISSION_MODE_VALUES,
            "description", "子 Agent 权限模式。默认请省略或使用 DEFAULT_EXECUTE；兼容 useDefault/use_default。"
        );
    }

    static Map<String, Object> permissionRuntimeStateSchema() {
        return Map.of(
            "type", "object",
            "description", "子 Agent canonical 权限运行态。新协议优先使用该字段；permissionMode 仅作为兼容旧入口。"
        );
    }

    static Map<String, Object> agentModeSchema() {
        return Map.of(
            "type", "string",
            "enum", AGENT_MODE_VALUES,
            "description", "子 Agent 模式。通用执行任务请省略或使用 EXECUTE；兼容 general。"
        );
    }

    static Map<String, Object> thinkingLevelSchema() {
        return Map.of(
            "type", "string",
            "enum", List.of("LOW", "MEDIUM", "HIGH", "MAX"),
            "description", "推理强度。通常省略以继承父 session；只有用户明确指定时填写。可用值: LOW, MEDIUM, HIGH, MAX。"
        );
    }

    static Map<String, Object> modelSchema() {
        return Map.of(
            "type", "string",
            "description", "子 Agent 模型。通常省略以继承父 session 当前模型；只有用户明确要求某个模型时填写。裸模型名使用当前唯一 provider/openai，provider/model 形式仅用于兼容。"
        );
    }
}
