package cn.lypi.tool;

import cn.lypi.contracts.event.EventBus;
import cn.lypi.contracts.event.PermissionDecisionEvent;
import cn.lypi.contracts.event.PermissionRequestEvent;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.tool.Tool;
import cn.lypi.contracts.tool.ToolUseContext;
import cn.lypi.contracts.tool.ToolUseRequest;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 发布权限确认请求和最终确认结果的 gate。
 *
 * NOTE: 该类只依赖事件总线和委托 gate，不直接依赖 TUI 或 headless transport。
 */
public final class EventPublishingPermissionGate implements PermissionGate {
    private final EventBus eventBus;
    private final PermissionGate delegate;

    public EventPublishingPermissionGate(EventBus eventBus, PermissionGate delegate) {
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus must not be null");
        this.delegate = delegate == null ? PermissionGate.denying() : delegate;
    }

    @Override
    public PermissionGateResult request(
        ToolUseRequest request,
        Tool<?, ?> tool,
        ToolUseContext context,
        PermissionDecision decision
    ) {
        String renderedToolUse = renderToolUse(request, tool);
        String message = decisionMessage(decision);
        eventBus.publish(new PermissionRequestEvent(
            context.sessionId(),
            request.toolUseId(),
            tool.name(),
            renderedToolUse,
            message,
            decision,
            Instant.now()
        ));

        PermissionGateResult result = Optional.ofNullable(delegate.request(request, tool, context, decision))
            .orElseGet(() -> PermissionGateResult.deny(message));
        eventBus.publish(new PermissionDecisionEvent(
            context.sessionId(),
            request.toolUseId(),
            tool.name(),
            renderedToolUse,
            decisionFromResult(result, decision),
            Instant.now()
        ));
        return result;
    }

    @SuppressWarnings("unchecked")
    private String renderToolUse(ToolUseRequest request, Tool<?, ?> tool) {
        Tool<Map<String, Object>, ?> typedTool = (Tool<Map<String, Object>, ?>) tool;
        Map<String, Object> input = request.input() == null ? Map.of() : request.input();
        return typedTool.renderForUser(input);
    }

    private PermissionDecision decisionFromResult(PermissionGateResult result, PermissionDecision originalDecision) {
        PermissionBehavior behavior = switch (result.status()) {
            case ALLOW -> PermissionBehavior.ALLOW;
            case DENY, ABORT -> PermissionBehavior.DENY;
        };
        return new PermissionDecision(
            behavior,
            originalDecision.reason(),
            result.message().orElse(decisionMessage(originalDecision)),
            result.permissionUpdate().or(() -> originalDecision.suggestedUpdate()),
            originalDecision.metadata()
        );
    }

    private String decisionMessage(PermissionDecision decision) {
        if (decision == null || decision.message() == null || decision.message().isBlank()) {
            return "权限请求需要确认。";
        }
        return decision.message();
    }
}
