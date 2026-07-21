package cn.lypi.tool;

import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.tool.Tool;
import cn.lypi.contracts.tool.ToolUseContext;
import cn.lypi.contracts.tool.ToolUseRequest;

/**
 * 处理 AUTO 模式下的独立模型权限复核。
 */
@FunctionalInterface
public interface PermissionReviewer {
    PermissionGateResult review(
        ToolUseRequest request,
        Tool<?, ?> tool,
        ToolUseContext context,
        ContextSnapshot contextSnapshot,
        PermissionDecision decision
    );

    static PermissionReviewer denying() {
        return (request, tool, context, contextSnapshot, decision) ->
            PermissionGateResult.deny("AUTO 权限复核器不可用。");
    }
}
