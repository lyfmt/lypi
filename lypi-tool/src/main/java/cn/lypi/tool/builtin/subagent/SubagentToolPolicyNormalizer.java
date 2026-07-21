package cn.lypi.tool.builtin.subagent;

import cn.lypi.contracts.runtime.ToolRuntimePort;
import cn.lypi.contracts.subagent.SubagentToolPolicy;
import cn.lypi.contracts.tool.Tool;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

public final class SubagentToolPolicyNormalizer {
    private static final List<String> BASE_TOOLS = List.of("read", "grep", "glob");

    private final ToolRuntimePort runtime;

    public SubagentToolPolicyNormalizer(ToolRuntimePort runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime must not be null");
    }

    public SubagentToolPolicy normalize(List<String> requestedTools) {
        LinkedHashSet<String> requested = new LinkedHashSet<>(requestedTools == null ? List.of() : requestedTools);
        LinkedHashSet<String> effective = new LinkedHashSet<>(BASE_TOOLS);
        effective.addAll(requested);
        for (String name : effective) {
            Tool<?, ?> tool = runtime.resolve(name)
                .orElseThrow(() -> new IllegalArgumentException("工具不存在: " + name));
            if (!name.equals(tool.name())) {
                throw new IllegalArgumentException(
                    "tools 只接受 canonical 工具名；" + name + " 是 " + tool.name() + " 的别名。"
                );
            }
        }
        return new SubagentToolPolicy(List.copyOf(requested), List.copyOf(effective));
    }
}
