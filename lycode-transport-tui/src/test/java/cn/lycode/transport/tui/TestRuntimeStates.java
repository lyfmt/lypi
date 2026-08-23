package cn.lycode.transport.tui;

import cn.lycode.contracts.context.ContextBudget;
import cn.lycode.contracts.model.ModelSelection;
import cn.lycode.contracts.model.ThinkingLevel;
import cn.lycode.contracts.security.AgentMode;
import cn.lycode.contracts.security.PermissionMode;
import cn.lycode.contracts.tui.SessionRuntimeState;
import java.math.BigDecimal;
import java.nio.file.Path;

final class TestRuntimeStates {
    private TestRuntimeStates() {
    }

    static SessionRuntimeState basic(String sessionId) {
        return new SessionRuntimeState(
            sessionId,
            Path.of("/workspace/ly-code"),
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
            Path.of("/workspace/ly-code"),
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
