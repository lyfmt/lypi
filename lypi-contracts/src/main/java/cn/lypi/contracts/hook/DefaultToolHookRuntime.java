package cn.lypi.contracts.hook;

import cn.lypi.contracts.event.EventBus;
import cn.lypi.contracts.event.HookEndEvent;
import cn.lypi.contracts.event.HookStartEvent;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseContext;
import cn.lypi.contracts.tool.ToolUseRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class DefaultToolHookRuntime implements ToolHookRuntime {
    static final ToolHookRuntime NOOP = new DefaultToolHookRuntime(List.of());

    private final List<ToolHook> hooks;
    private final EventBus eventBus;

    public DefaultToolHookRuntime(List<ToolHook> hooks) {
        this(hooks, null);
    }

    public DefaultToolHookRuntime(List<ToolHook> hooks, EventBus eventBus) {
        this.hooks = List.copyOf(Objects.requireNonNull(hooks, "hooks"));
        this.eventBus = eventBus;
    }

    @Override
    public BeforeToolHookResult beforeToolCall(BeforeToolHookContext context) {
        BeforeToolHookContext nonNullContext = Objects.requireNonNull(context, "context");
        for (int index = 0; index < hooks.size(); index++) {
            ToolHook hook = hooks.get(index);
            HookRun run = start(nonNullContext.request(), nonNullContext.toolContext(), hook, HookPhase.BEFORE_TOOL_CALL, index);
            try {
                BeforeToolHookResult result = Objects.requireNonNull(
                    hook.beforeToolCall(nonNullContext),
                    "beforeToolCall result"
                );
                if (result.blocked()) {
                    end(run, HookRunStatus.BLOCKED, result.message());
                    return result;
                }
                end(run, HookRunStatus.SUCCEEDED, null);
            } catch (RuntimeException exception) {
                end(run, HookRunStatus.FAILED, exception.getMessage());
                throw exception;
            }
        }
        return BeforeToolHookResult.allow();
    }

    @Override
    public Optional<ToolResult<?>> afterToolCall(AfterToolHookContext context) {
        AfterToolHookContext currentContext = Objects.requireNonNull(context, "context");
        ToolResult<?> currentResult = currentContext.result();
        boolean replaced = false;
        for (int index = 0; index < hooks.size(); index++) {
            ToolHook hook = hooks.get(index);
            HookRun run = start(currentContext.request(), currentContext.toolContext(), hook, HookPhase.AFTER_TOOL_CALL, index);
            try {
                AfterToolHookResult hookResult = Objects.requireNonNull(
                    hook.afterToolCall(currentContext),
                    "afterToolCall result"
                );
                Optional<ToolResult<?>> replacement = hookResult.replacement();
                if (replacement.isPresent()) {
                    currentResult = replacement.orElseThrow();
                    replaced = true;
                    currentContext = new AfterToolHookContext(
                        currentContext.request(),
                        currentContext.tool(),
                        currentContext.input(),
                        currentContext.toolContext(),
                        currentResult
                    );
                    end(run, HookRunStatus.REPLACED, "工具结果已替换。");
                } else {
                    end(run, HookRunStatus.SUCCEEDED, null);
                }
            } catch (RuntimeException exception) {
                end(run, HookRunStatus.FAILED, exception.getMessage());
                throw exception;
            }
        }
        return replaced ? Optional.of(currentResult) : Optional.empty();
    }

    private HookRun start(
        ToolUseRequest request,
        ToolUseContext context,
        ToolHook hook,
        HookPhase phase,
        int index
    ) {
        Instant startedAt = Instant.now();
        String hookRunId = hookRunId(request.toolUseId(), phase, index);
        HookRun run = new HookRun(
            safeText(context.sessionId(), "session_unknown"),
            safeText(request.toolUseId(), "toolu_unknown"),
            request.parentMessageId(),
            turnId(context),
            safeText(request.toolName(), "tool_unknown"),
            hookRunId,
            hookName(hook),
            phase,
            startedAt
        );
        publish(new HookStartEvent(
            run.sessionId(),
            run.toolUseId(),
            run.parentMessageId(),
            run.turnId(),
            run.toolName(),
            run.hookRunId(),
            run.hookName(),
            run.phase(),
            run.startedAt(),
            startedAt
        ));
        return run;
    }

    private void end(HookRun run, HookRunStatus status, String message) {
        Instant endedAt = Instant.now();
        long durationMillis = Math.max(0L, Duration.between(run.startedAt(), endedAt).toMillis());
        publish(new HookEndEvent(
            run.sessionId(),
            run.toolUseId(),
            run.parentMessageId(),
            run.turnId(),
            run.toolName(),
            run.hookRunId(),
            run.hookName(),
            run.phase(),
            status,
            message,
            run.startedAt(),
            endedAt,
            durationMillis,
            endedAt
        ));
    }

    private void publish(cn.lypi.contracts.event.AgentEvent event) {
        if (eventBus == null) {
            return;
        }
        try {
            eventBus.publish(event);
        } catch (RuntimeException exception) {
            // NOTE: hook 审计事件发布失败不得改变工具执行主链路。
        }
    }

    private String turnId(ToolUseContext context) {
        Object value = context.metadata() == null ? null : context.metadata().get("turnId");
        return value == null ? null : value.toString();
    }

    private String hookRunId(String toolUseId, HookPhase phase, int index) {
        return "hook_" + safeText(toolUseId, "toolu_unknown") + "_" + phaseToken(phase) + "_" + index;
    }

    private String hookName(ToolHook hook) {
        String name = hook.name();
        return name == null || name.isBlank() ? hook.getClass().getName() : name;
    }

    private String phaseToken(HookPhase phase) {
        return phase == HookPhase.BEFORE_TOOL_CALL ? "before" : "after";
    }

    private String safeText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private record HookRun(
        String sessionId,
        String toolUseId,
        String parentMessageId,
        String turnId,
        String toolName,
        String hookRunId,
        String hookName,
        HookPhase phase,
        Instant startedAt
    ) {}
}
