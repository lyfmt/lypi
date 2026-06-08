package cn.lypi.contracts.runtime;

import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.tool.Tool;
import cn.lypi.contracts.tool.ToolRegistrySnapshot;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseRequest;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public interface ToolRuntimePort {
    /**
     * 注册一个工具协议对象。
     *
     * NOTE: 注册过程必须处理名称冲突、别名冲突和 diagnostic 生成。
     */
    void register(Tool<?, ?> tool);

    /**
     * 按工具名称或别名查找工具。
     *
     * NOTE: 查找结果必须保留主工具名用于审计。
     */
    Optional<Tool<?, ?>> resolve(String nameOrAlias);

    /**
     * 生成工具注册表快照。
     *
     * NOTE: 快照供启动上下文、诊断和 prompt 构建消费，不暴露工具实现对象。
     */
    ToolRegistrySnapshot snapshot();

    /**
     * 返回工具运行时工作目录。
     *
     * NOTE: 该目录必须与 agent-core/resource runtime 使用的 cwd 保持一致，避免模型上下文和工具执行作用于不同项目。
     */
    Path cwd();

    /**
     * 编排并执行模型发出的工具调用。
     *
     * NOTE: 必须完成解析、校验、权限、并发规划、执行和结果预算处理。
     * NOTE: 工具调用生命周期由工具运行时按单个工具真实调度与完成边界发布。
     */
    List<ToolResult<?>> execute(List<ToolUseRequest> requests, ContextSnapshot context);

    /**
     * 在指定上层归属下编排并执行模型发出的工具调用。
     *
     * NOTE: agent-core 必须通过该入口传入真实 sessionId 与 turnId，供工具生命周期事件归属使用。
     * NOTE: 自定义 runtime 若需要发布工具生命周期事件，应重写该入口并使用 invocation 归属信息。
     */
    default List<ToolResult<?>> execute(
        List<ToolUseRequest> requests,
        ContextSnapshot context,
        ToolRuntimeInvocation invocation
    ) {
        return execute(requests, context);
    }
}
