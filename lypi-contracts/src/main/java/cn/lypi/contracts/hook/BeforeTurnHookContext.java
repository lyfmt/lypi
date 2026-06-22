package cn.lypi.contracts.hook;

import cn.lypi.contracts.agent.TurnRequest;
import java.nio.file.Path;
import java.util.Objects;

/**
 * 表示 turn 执行前 hook 的上下文。
 */
public record BeforeTurnHookContext(
    TurnRequest request,
    String turnId,
    Path cwd
) {
    public BeforeTurnHookContext {
        request = Objects.requireNonNull(request, "request");
        turnId = Objects.requireNonNull(turnId, "turnId");
        cwd = Objects.requireNonNull(cwd, "cwd").toAbsolutePath().normalize();
    }
}
