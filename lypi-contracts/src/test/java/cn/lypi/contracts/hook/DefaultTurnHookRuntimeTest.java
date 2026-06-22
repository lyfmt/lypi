package cn.lypi.contracts.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import cn.lypi.contracts.agent.TurnRequest;
import cn.lypi.contracts.agent.TurnState;
import cn.lypi.contracts.agent.TurnStatus;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DefaultTurnHookRuntimeTest {
    @Test
    void turnHookDefaultsToNoOpBeforeAndAfter() {
        TurnHook hook = new TurnHook() {
        };

        BeforeTurnHookResult beforeResult = hook.beforeTurn(beforeContext());
        AfterTurnHookResult afterResult = hook.afterTurn(afterContext(state(TurnStatus.COMPLETED, 0)));

        assertFalse(beforeResult.blocked());
        assertNull(beforeResult.message());
        assertEquals(AfterTurnHookResult.keep(), afterResult);
    }

    @Test
    void noopRuntimeAllowsBeforeAndKeepsAfter() {
        TurnHookRuntime runtime = TurnHookRuntime.noop();

        BeforeTurnHookResult beforeResult = runtime.beforeTurn(beforeContext());
        runtime.afterTurn(afterContext(state(TurnStatus.COMPLETED, 0)));

        assertFalse(beforeResult.blocked());
        assertNull(beforeResult.message());
    }

    @Test
    void beforeStopsAtFirstBlockingHook() {
        TurnHook first = TurnHook.before(context -> BeforeTurnHookResult.block("blocked"));
        TurnHook second = TurnHook.before(context -> {
            fail("阻断后不应继续执行后续 before turn hook");
            return BeforeTurnHookResult.allow();
        });

        BeforeTurnHookResult result = new DefaultTurnHookRuntime(List.of(first, second))
            .beforeTurn(beforeContext());

        assertTrue(result.blocked());
        assertEquals("blocked", result.message());
    }

    @Test
    void beforeAllowsWhenNoHookBlocks() {
        TurnHook first = TurnHook.before(context -> BeforeTurnHookResult.allow());
        TurnHook second = TurnHook.before(context -> BeforeTurnHookResult.allow());

        BeforeTurnHookResult result = new DefaultTurnHookRuntime(List.of(first, second))
            .beforeTurn(beforeContext());

        assertFalse(result.blocked());
        assertNull(result.message());
    }

    @Test
    void afterRunsAllHooksInOrder() {
        List<TurnStatus> observed = new ArrayList<>();
        TurnHook first = TurnHook.after(context -> {
            observed.add(context.state().status());
            return AfterTurnHookResult.keep();
        });
        TurnHook second = TurnHook.after(context -> {
            observed.add(context.state().status());
            return AfterTurnHookResult.keep();
        });

        new DefaultTurnHookRuntime(List.of(first, second))
            .afterTurn(afterContext(state(TurnStatus.COMPLETED, 0)));

        assertEquals(List.of(TurnStatus.COMPLETED, TurnStatus.COMPLETED), observed);
    }

    @Test
    void afterReturnsEmptyWhenHooksKeepOriginal() {
        TurnHook first = TurnHook.after(context -> AfterTurnHookResult.keep());
        TurnHook second = TurnHook.after(context -> AfterTurnHookResult.keep());

        new DefaultTurnHookRuntime(List.of(first, second))
            .afterTurn(afterContext(state(TurnStatus.COMPLETED, 0)));
    }

    @Test
    void runtimeDefensivelyCopiesHookList() {
        List<TurnHook> hooks = new ArrayList<>();
        hooks.add(TurnHook.before(context -> BeforeTurnHookResult.allow()));

        DefaultTurnHookRuntime runtime = new DefaultTurnHookRuntime(hooks);

        hooks.clear();
        hooks.add(TurnHook.before(context -> BeforeTurnHookResult.block("late-block")));

        BeforeTurnHookResult result = runtime.beforeTurn(beforeContext());

        assertFalse(result.blocked());
    }

    @Test
    void contextsRejectRequiredNulls() {
        TurnRequest request = request();
        TurnState state = state(TurnStatus.COMPLETED, 0);

        assertThrows(NullPointerException.class, () -> new BeforeTurnHookContext(null, "turn-1", Path.of(".")));
        assertThrows(NullPointerException.class, () -> new BeforeTurnHookContext(request, null, Path.of(".")));
        assertThrows(NullPointerException.class, () -> new BeforeTurnHookContext(request, "turn-1", null));
        assertThrows(NullPointerException.class, () -> new AfterTurnHookContext(null, state, Path.of(".")));
        assertThrows(NullPointerException.class, () -> new AfterTurnHookContext(request, null, Path.of(".")));
        assertThrows(NullPointerException.class, () -> new AfterTurnHookContext(request, state, null));
    }

    private static BeforeTurnHookContext beforeContext() {
        return new BeforeTurnHookContext(request(), "turn-1", Path.of("/tmp/project"));
    }

    private static AfterTurnHookContext afterContext(TurnState state) {
        return new AfterTurnHookContext(request(), state, Path.of("/tmp/project"));
    }

    private static TurnRequest request() {
        return new TurnRequest("session-1", "hello", Optional.empty(), () -> false);
    }

    private static TurnState state(TurnStatus status, int toolRound) {
        return new TurnState("turn-1", "session-1", null, List.of(), toolRound, status);
    }
}
