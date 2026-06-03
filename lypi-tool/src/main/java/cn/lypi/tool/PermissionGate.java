package cn.lypi.tool;

import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.tool.Tool;
import cn.lypi.contracts.tool.ToolUseContext;
import cn.lypi.contracts.tool.ToolUseRequest;

/**
 * 处理需要用户确认的权限请求。
 *
 * NOTE: 默认实现必须 fail-safe，避免非交互运行环境因 ASK 决策挂起。
 */
@FunctionalInterface
public interface PermissionGate {
    /**
     * 请求确认一次 ASK 权限决策。
     *
     * 返回值决定运行时继续执行、拒绝执行或中断工具调用。
     */
    PermissionGateResult request(
        ToolUseRequest request,
        Tool<?, ?> tool,
        ToolUseContext context,
        PermissionDecision decision
    );

    /**
     * 返回非交互安全默认 gate。
     *
     * 默认拒绝 ASK 决策，不阻塞等待用户输入。
     */
    static PermissionGate denying() {
        return (request, tool, context, decision) -> PermissionGateResult.deny(decision == null ? null : decision.message());
    }
}
