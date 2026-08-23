package cn.lycode.tool;

import cn.lycode.contracts.context.ContextSnapshot;
import cn.lycode.contracts.tool.ToolResult;
import cn.lycode.contracts.tool.ToolUseRequest;
import java.util.List;

public interface ToolOrchestrator {
    /**
     * 编排并执行模型发出的工具调用。
     *
     * NOTE: 必须完成解析、校验、权限、并发规划、执行和结果预算处理。
     */
    List<ToolResult<?>> execute(List<ToolUseRequest> requests, ContextSnapshot context);
}
