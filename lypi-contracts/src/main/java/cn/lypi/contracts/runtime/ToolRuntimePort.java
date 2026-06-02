package cn.lypi.contracts.runtime;

import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.tool.Tool;
import cn.lypi.contracts.tool.ToolRegistrySnapshot;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseRequest;
import java.util.List;
import java.util.Optional;

public interface ToolRuntimePort {
    /**
     * TODO: 注册一个工具协议对象。
     *
     * 注册过程必须处理名称冲突、别名冲突和 diagnostic 生成。
     */
    void register(Tool<?, ?> tool);

    /**
     * TODO: 按工具名称或别名查找工具。
     *
     * 查找结果必须保留主工具名用于审计。
     */
    Optional<Tool<?, ?>> resolve(String nameOrAlias);

    /**
     * TODO: 生成工具注册表快照。
     *
     * 快照供启动上下文、诊断和 prompt 构建消费，不暴露工具实现对象。
     */
    ToolRegistrySnapshot snapshot();

    /**
     * TODO: 编排并执行模型发出的工具调用。
     *
     * 必须完成解析、校验、权限、并发规划、执行和结果预算处理。
     */
    List<ToolResult<?>> execute(List<ToolUseRequest> requests, ContextSnapshot context);
}
