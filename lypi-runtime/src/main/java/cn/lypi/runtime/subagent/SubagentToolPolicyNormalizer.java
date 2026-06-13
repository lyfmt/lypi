package cn.lypi.runtime.subagent;

import cn.lypi.contracts.runtime.ToolRuntimePort;
import cn.lypi.contracts.subagent.SubagentToolPolicy;
import cn.lypi.contracts.tool.Tool;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class SubagentToolPolicyNormalizer {
    public static final List<String> BASE_READ_TOOLS = List.of("read", "grep", "glob");

    private SubagentToolPolicyNormalizer() {
    }

    /**
     * 合并模型请求工具和子 Agent 基础读工具。
     *
     * NOTE: 未知工具保留在策略中，由实际 ToolRuntime 在执行或过滤阶段返回诊断。
     */
    public static SubagentToolPolicy normalize(List<String> requestedTools, ToolRuntimePort runtime) {
        LinkedHashSet<String> requested = normalizeToolNames(requestedTools, runtime);
        LinkedHashSet<String> effective = normalizeToolNames(BASE_READ_TOOLS, runtime);
        effective.addAll(requested);
        return new SubagentToolPolicy(List.copyOf(requested), List.copyOf(effective));
    }

    private static LinkedHashSet<String> normalizeToolNames(List<String> toolNames, ToolRuntimePort runtime) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (toolNames == null) {
            return normalized;
        }
        for (String toolName : toolNames) {
            String cleaned = clean(toolName);
            if (cleaned == null) {
                continue;
            }
            normalized.add(canonicalName(cleaned, runtime));
        }
        return normalized;
    }

    private static String canonicalName(String toolName, ToolRuntimePort runtime) {
        if (runtime == null) {
            return toolName;
        }
        return runtime.resolve(toolName)
            .map(Tool::name)
            .map(SubagentToolPolicyNormalizer::clean)
            .orElse(toolName);
    }

    private static String clean(String toolName) {
        if (toolName == null) {
            return null;
        }
        String cleaned = toolName.trim();
        return cleaned.isEmpty() ? null : cleaned;
    }
}
