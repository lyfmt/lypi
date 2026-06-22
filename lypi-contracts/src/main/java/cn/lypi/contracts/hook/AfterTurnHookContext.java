package cn.lypi.contracts.hook;

import cn.lypi.contracts.agent.TurnRequest;
import cn.lypi.contracts.agent.TurnState;
import java.nio.file.Path;
import java.util.Objects;

/**
 * 表示 turn 执行后 hook 的上下文。
 */
public record AfterTurnHookContext(
    TurnRequest request,
    TurnState state,
    Path cwd
) {
    public AfterTurnHookContext {
        request = Objects.requireNonNull(request, "request");
        state = Objects.requireNonNull(state, "state");
        cwd = Objects.requireNonNull(cwd, "cwd").toAbsolutePath().normalize();
    }
}
