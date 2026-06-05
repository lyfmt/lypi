package cn.lypi.tool;

import cn.lypi.contracts.event.EventBus;
import cn.lypi.contracts.event.PermissionDecisionEvent;
import cn.lypi.contracts.event.PermissionRequestEvent;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionOption;
import cn.lypi.contracts.security.PermissionOptionKind;
import cn.lypi.contracts.security.PermissionOptionPolicy;
import cn.lypi.contracts.security.PermissionResponse;
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
    private final PermissionGate legacyDelegate;
    private final PermissionResponseGate responseDelegate;

    public EventPublishingPermissionGate(EventBus eventBus, PermissionGate delegate) {
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus must not be null");
        this.legacyDelegate = delegate == null ? PermissionGate.denying() : delegate;
        this.responseDelegate = null;
    }

    public EventPublishingPermissionGate(EventBus eventBus, PermissionResponseGate delegate) {
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus must not be null");
        this.legacyDelegate = null;
        this.responseDelegate = delegate == null ? requestEvent -> fallbackDenyResponse(requestEvent) : delegate;
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
        PermissionOptionPolicy.Options optionPolicy = PermissionOptionPolicy.fromDecision(decision);
        PermissionRequestEvent requestEvent = new PermissionRequestEvent(
            context.sessionId(),
            requestId(request),
            request.toolUseId(),
            tool.name(),
            message,
            renderedToolUse,
            message,
            decision,
            optionPolicy.options(),
            optionPolicy.defaultOptionId(),
            optionPolicy.cancelOptionId(),
            Map.of("toolName", tool.name()),
            Instant.now()
        );
        eventBus.publish(requestEvent);

        PermissionResponse response = Optional.ofNullable(response(requestEvent, request, tool, context, decision))
            .orElseGet(() -> fallbackDenyResponse(requestEvent));
        boolean responseMatches = responseMatches(requestEvent, response);
        if (!responseMatches) {
            response = fallbackDenyResponse(requestEvent);
        }
        PermissionOption selectedOption = selectedOption(requestEvent, response);
        PermissionGateResult result = resultFromOption(selectedOption, response, message);
        eventBus.publish(new PermissionDecisionEvent(
            context.sessionId(),
            requestEvent.requestId(),
            request.toolUseId(),
            tool.name(),
            renderedToolUse,
            selectedOption.optionId(),
            decisionFromResult(result, selectedOption, decision),
            Optional.empty(),
            decisionMetadata(selectedOption, responseMatches),
            Instant.now()
        ));
        return result;
    }

    private PermissionResponse response(
        PermissionRequestEvent requestEvent,
        ToolUseRequest request,
        Tool<?, ?> tool,
        ToolUseContext context,
        PermissionDecision decision
    ) {
        if (responseDelegate != null) {
            return responseDelegate.request(requestEvent);
        }
        return responseFromLegacyGate(requestEvent, request, tool, context, decision);
    }

    private PermissionResponse responseFromLegacyGate(
        PermissionRequestEvent requestEvent,
        ToolUseRequest request,
        Tool<?, ?> tool,
        ToolUseContext context,
        PermissionDecision decision
    ) {
        PermissionGateResult result = Optional.ofNullable(legacyDelegate.request(request, tool, context, decision))
            .orElseGet(() -> PermissionGateResult.deny(requestEvent.message()));
        String optionId = switch (result.status()) {
            case ALLOW -> result.permissionUpdate().isPresent() ? rememberOptionId(requestEvent) : "allow_once";
            case DENY -> "deny";
            case ABORT -> requestEvent.cancelOptionId();
        };
        return new PermissionResponse(requestEvent.sessionId(), requestEvent.requestId(), optionId, false, Instant.now());
    }

    private PermissionResponse fallbackDenyResponse(PermissionRequestEvent requestEvent) {
        return new PermissionResponse(requestEvent.sessionId(), requestEvent.requestId(), "deny", false, Instant.now());
    }

    @SuppressWarnings("unchecked")
    private String renderToolUse(ToolUseRequest request, Tool<?, ?> tool) {
        Tool<Map<String, Object>, ?> typedTool = (Tool<Map<String, Object>, ?>) tool;
        Map<String, Object> input = request.input() == null ? Map.of() : request.input();
        return typedTool.renderForUser(input);
    }

    private PermissionDecision decisionFromResult(PermissionGateResult result, PermissionDecision originalDecision) {
        return decisionFromResult(result, null, originalDecision);
    }

    private PermissionDecision decisionFromResult(
        PermissionGateResult result,
        PermissionOption selectedOption,
        PermissionDecision originalDecision
    ) {
        PermissionBehavior behavior = switch (result.status()) {
            case ALLOW -> PermissionBehavior.ALLOW;
            case DENY, ABORT -> PermissionBehavior.DENY;
        };
        return new PermissionDecision(
            behavior,
            originalDecision.reason(),
            result.message().orElse(decisionMessage(originalDecision)),
            result.permissionUpdate()
                .or(() -> selectedOption == null ? Optional.empty() : selectedOption.permissionUpdate())
                .or(() -> originalDecision.suggestedUpdate()),
            originalDecision.metadata()
        );
    }

    private PermissionOption selectedOption(PermissionRequestEvent requestEvent, PermissionResponse response) {
        if (response.fromKeyboardCancel()) {
            return requestEvent.options().stream()
                .filter(option -> option.optionId().equals(requestEvent.cancelOptionId()))
                .findFirst()
                .orElseGet(() -> requestEvent.options().stream()
                    .filter(option -> option.kind() == PermissionOptionKind.DENY)
                    .findFirst()
                    .orElse(requestEvent.options().getFirst()));
        }
        return requestEvent.options().stream()
            .filter(option -> option.optionId().equals(response.selectedOptionId()))
            .findFirst()
            .orElseGet(() -> requestEvent.options().stream()
                .filter(option -> option.kind() == PermissionOptionKind.DENY)
                .findFirst()
                .orElse(requestEvent.options().getFirst()));
    }

    private PermissionGateResult resultFromOption(PermissionOption selectedOption, PermissionResponse response, String message) {
        if (response.fromKeyboardCancel() || selectedOption.kind() == PermissionOptionKind.CANCEL) {
            return PermissionGateResult.abort(message);
        }
        return switch (selectedOption.kind()) {
            case ALLOW_ONCE -> PermissionGateResult.allow();
            case ALLOW_AND_REMEMBER -> PermissionGateResult.allow(selectedOption.permissionUpdate());
            case DENY -> PermissionGateResult.deny(message);
            case CANCEL -> PermissionGateResult.abort(message);
        };
    }

    private boolean responseMatches(PermissionRequestEvent requestEvent, PermissionResponse response) {
        return Objects.equals(requestEvent.sessionId(), response.sessionId())
            && Objects.equals(requestEvent.requestId(), response.requestId());
    }

    private Map<String, Object> decisionMetadata(PermissionOption selectedOption, boolean responseMatches) {
        if (!responseMatches) {
            return Map.of("updateStatus", "response_mismatch");
        }
        if (selectedOption.kind() == PermissionOptionKind.ALLOW_AND_REMEMBER) {
            return Map.of("updateStatus", "pending_external_application");
        }
        return Map.of("updateStatus", "selected");
    }

    private String rememberOptionId(PermissionRequestEvent requestEvent) {
        return requestEvent.options().stream()
            .filter(option -> option.kind() == PermissionOptionKind.ALLOW_AND_REMEMBER)
            .map(PermissionOption::optionId)
            .findFirst()
            .orElse("allow_once");
    }

    private String requestId(ToolUseRequest request) {
        return "perm_" + request.toolUseId();
    }

    private String decisionMessage(PermissionDecision decision) {
        if (decision == null || decision.message() == null || decision.message().isBlank()) {
            return "权限请求需要确认。";
        }
        return decision.message();
    }
}
