package cn.lypi.contracts.hook;

import cn.lypi.contracts.agent.TurnState;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

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
    public Optional<TurnState> afterTurn(AfterTurnHookContext context) {
        AfterTurnHookContext currentContext = Objects.requireNonNull(context, "context");
        TurnState currentState = currentContext.state();
        boolean replaced = false;
        for (TurnHook hook : hooks) {
            AfterTurnHookResult hookResult = Objects.requireNonNull(
                hook.afterTurn(currentContext),
                "afterTurn result"
            );
            Optional<TurnState> replacement = hookResult.replacement();
            if (replacement.isPresent()) {
                currentState = replacement.orElseThrow();
                replaced = true;
                currentContext = new AfterTurnHookContext(
                    currentContext.request(),
                    currentState,
                    currentContext.cwd()
                );
            }
        }
        return replaced ? Optional.of(currentState) : Optional.empty();
    }
}
