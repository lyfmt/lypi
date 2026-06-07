package cn.lypi.tool;

import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.tool.Tool;
import cn.lypi.contracts.tool.ToolUseContext;
import cn.lypi.contracts.tool.ToolUseRequest;

/**
 * 抽象本地权限提示交互。
 *
 * NOTE: 该端口只表达进程内等待，不负责跨 turn 恢复或持久化权限决策。
 */
@FunctionalInterface
public interface PermissionPromptPort {
    /**
     * 等待用户对一次 ASK 权限请求作出选择。
     *
     * 返回值决定工具继续执行、拒绝执行或中断工具调用。
     */
    PermissionGateResult ask(Handle handle) throws InterruptedException;

    /**
     * 一次权限提示所需的上下文。
     */
    record Handle(
        ToolUseRequest request,
        Tool<?, ?> tool,
        ToolUseContext context,
        PermissionDecision decision
    ) {}
}
