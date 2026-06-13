package cn.lypi.boot.tool;

import cn.lypi.contracts.runtime.ToolRuntimePort;
import cn.lypi.contracts.subagent.SubagentToolPolicy;
import java.nio.file.Path;

/**
 * 创建绑定到指定 cwd 的工具运行时。
 */
@FunctionalInterface
public interface ToolRuntimeFactoryPort {
    /**
     * 创建绑定到 cwd 的工具运行时。
     */
    ToolRuntimePort create(Path cwd);

    /**
     * 创建绑定到 cwd 且应用子 Agent 工具策略的工具运行时。
     */
    default ToolRuntimePort create(Path cwd, SubagentToolPolicy toolPolicy) {
        return create(cwd);
    }
}
