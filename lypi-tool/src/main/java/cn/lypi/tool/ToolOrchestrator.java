package cn.lypi.tool;

import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseRequest;
import java.util.List;

public interface ToolOrchestrator {
    /*
    * @status : 未完成
    * @summary : 编排并执行模型发出的工具调用。
    *@description : 必须完成解析、校验、权限、并发规划、执行和结果预算处理。
    *
    *
                              */
    List<ToolResult<?>> execute(List<ToolUseRequest> requests, ContextSnapshot context);
}

