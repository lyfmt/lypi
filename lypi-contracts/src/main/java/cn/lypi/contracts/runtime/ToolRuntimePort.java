package cn.lypi.contracts.runtime;

import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.security.PermissionResponse;
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
     */
    List<ToolResult<?>> execute(List<ToolUseRequest> requests, ContextSnapshot context);

    /**
     * 恢复一次等待用户确认的工具调用。
     *
     * NOTE: 仅恢复 `request.toolUseId()` 对应的单个调用，不得重新执行同批次其他工具。
     */
    default ToolResult<?> resume(ToolUseRequest request, ContextSnapshot context, PermissionResponse response) {
        throw new UnsupportedOperationException("工具运行时暂不支持权限恢复。");
    }
}
