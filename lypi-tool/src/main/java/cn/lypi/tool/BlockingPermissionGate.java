package cn.lypi.tool;

import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.tool.Tool;
import cn.lypi.contracts.tool.ToolUseContext;
import cn.lypi.contracts.tool.ToolUseRequest;

/**
 * 通过本地 prompt 端口等待 ASK 权限决策的 gate。
 *
 * NOTE: 权限事件由外层 EventPublishingPermissionGate 发布，本类不发布事件。
 */
public final class BlockingPermissionGate implements PermissionGate {
    private static final String DEFAULT_DENY_MESSAGE = "权限请求未获允许。";

    private final PermissionPromptPort promptPort;

    public BlockingPermissionGate(PermissionPromptPort promptPort) {
        this.promptPort = promptPort;
    }

    @Override
    public PermissionGateResult request(
        ToolUseRequest request,
        Tool<?, ?> tool,
        ToolUseContext context,
        PermissionDecision decision
    ) {
        if (promptPort == null) {
            return deny(decision);
        }
        try {
            PermissionPromptPort.Handle handle = new PermissionPromptPort.Handle(request, tool, context, decision);
            PermissionGateResult result = promptPort.ask(handle);
            return result == null ? PermissionGateResult.deny(DEFAULT_DENY_MESSAGE) : result;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return PermissionGateResult.abort(messageOrDefault(exception.getMessage(), "权限请求已中断。"));
        } catch (RuntimeException exception) {
            return PermissionGateResult.deny(messageOrDefault(exception.getMessage(), DEFAULT_DENY_MESSAGE));
        }
    }

    private PermissionGateResult deny(PermissionDecision decision) {
        if (decision == null || decision.message() == null || decision.message().isBlank()) {
            return PermissionGateResult.deny(DEFAULT_DENY_MESSAGE);
        }
        return PermissionGateResult.deny(decision.message());
    }

    private String messageOrDefault(String message, String defaultMessage) {
        return message == null || message.isBlank() ? defaultMessage : message;
    }
}
