package cn.lypi.transport.tui;

import cn.lypi.contracts.context.ContextBudget;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.tui.SessionRuntimeState;
import java.math.BigDecimal;
import java.nio.file.Path;

final class TestRuntimeStates {
    private TestRuntimeStates() {
    }

    static SessionRuntimeState basic(String sessionId) {
        return new SessionRuntimeState(
            sessionId,
            Path.of("/home/lyfmt/src/study/ly-pi"),
            "leaf_1",
            new ModelSelection("openai", "gpt-5.4", ThinkingLevel.HIGH),
            ThinkingLevel.HIGH,
            AgentMode.EXECUTE,
            PermissionMode.ASK,
            new ContextBudget(1234, 200000, 180000, 12000, 6000, 0, 0, BigDecimal.ZERO),
            false,
            false,
            false,
            false
        );
    }

    static SessionRuntimeState interruptible(String sessionId) {
        return new SessionRuntimeState(
            sessionId,
            Path.of("/home/lyfmt/src/study/ly-pi"),
            "leaf_1",
            new ModelSelection("openai", "gpt-5.4", ThinkingLevel.HIGH),
            ThinkingLevel.HIGH,
            AgentMode.EXECUTE,
            PermissionMode.ASK,
            new ContextBudget(1234, 200000, 180000, 12000, 6000, 0, 0, BigDecimal.ZERO),
            true,
            false,
            false,
            false
        );
    }
}
