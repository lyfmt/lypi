package cn.lycode.tool;

import cn.lycode.contracts.context.ContextSnapshot;
import cn.lycode.contracts.security.PermissionDecision;
import cn.lycode.contracts.tool.Tool;
import cn.lycode.contracts.tool.ToolUseContext;
import cn.lycode.contracts.tool.ToolUseRequest;

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
