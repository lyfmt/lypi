package cn.lypi.contracts.hook;

import java.util.List;
import java.util.Objects;

public final class DefaultTurnHookRuntime implements TurnHookRuntime {
    static final TurnHookRuntime NOOP = new DefaultTurnHookRuntime(List.of());

    private final List<TurnHook> hooks;

    public DefaultTurnHookRuntime(List<TurnHook> hooks) {
        this.hooks = List.copyOf(Objects.requireNonNull(hooks, "hooks"));
    }

    @Override
    public BeforeTurnHookResult beforeTurn(BeforeTurnHookContext context) {
        BeforeTurnHookContext nonNullContext = Objects.requireNonNull(context, "context");
        for (TurnHook hook : hooks) {
            BeforeTurnHookResult result = Objects.requireNonNull(
                hook.beforeTurn(nonNullContext),
                "beforeTurn result"
            );
            if (result.blocked()) {
                return result;
            }
        }
        return BeforeTurnHookResult.allow();
    }

    @Override
    public void afterTurn(AfterTurnHookContext context) {
        AfterTurnHookContext nonNullContext = Objects.requireNonNull(context, "context");
        for (TurnHook hook : hooks) {
            Objects.requireNonNull(
                hook.afterTurn(nonNullContext),
                "afterTurn result"
            );
        }
    }
}
