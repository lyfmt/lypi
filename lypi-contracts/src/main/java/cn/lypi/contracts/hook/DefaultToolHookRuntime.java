package cn.lypi.contracts.hook;

import cn.lypi.contracts.tool.ToolResult;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class DefaultToolHookRuntime implements ToolHookRuntime {
    static final ToolHookRuntime NOOP = new DefaultToolHookRuntime(List.of());

    private final List<ToolHook> hooks;

    public DefaultToolHookRuntime(List<ToolHook> hooks) {
        this.hooks = List.copyOf(Objects.requireNonNull(hooks, "hooks"));
    }

    @Override
    public BeforeToolHookResult beforeToolCall(BeforeToolHookContext context) {
        BeforeToolHookContext nonNullContext = Objects.requireNonNull(context, "context");
        for (ToolHook hook : hooks) {
            BeforeToolHookResult result = Objects.requireNonNull(
                hook.beforeToolCall(nonNullContext),
                "beforeToolCall result"
            );
            if (result.blocked()) {
                return result;
            }
        }
        return BeforeToolHookResult.allow();
    }

    @Override
    public Optional<ToolResult<?>> afterToolCall(AfterToolHookContext context) {
        AfterToolHookContext currentContext = Objects.requireNonNull(context, "context");
        ToolResult<?> currentResult = currentContext.result();
        boolean replaced = false;
        for (ToolHook hook : hooks) {
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
            }
        }
        return replaced ? Optional.of(currentResult) : Optional.empty();
    }
}
