package cn.lypi.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.runtime.ExecutionResult;
import cn.lypi.contracts.runtime.Executor;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionDecisionReason;
import cn.lypi.contracts.security.PermissionUpdate;
import cn.lypi.contracts.tool.Tool;
import cn.lypi.tool.DefaultToolRuntime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class BuiltInToolsTest {
    @Test
    void createsDefaultToolSet() {
        List<Tool<?, ?>> tools = BuiltInTools.createDefaultTools(executor());

        Set<String> names = tools.stream().map(Tool::name).collect(Collectors.toSet());

        assertEquals(Set.of("read", "write", "edit", "bash", "grep", "glob"), names);
    }

    @Test
    void registersDefaultsIntoRuntime() {
        DefaultToolRuntime runtime = new DefaultToolRuntime((request, context) ->
            new PermissionDecision(
                PermissionBehavior.ALLOW,
                PermissionDecisionReason.TOOL_SPECIFIC,
                "allowed",
                Optional.<PermissionUpdate>empty(),
                Map.of()
            )
        );

        BuiltInTools.registerDefaults(runtime, executor());

        assertTrue(runtime.resolve("read").isPresent());
        assertTrue(runtime.resolve("bash").isPresent());
    }

    private Executor executor() {
        return new Executor() {
            @Override
            public String name() {
                return "test";
            }

            @Override
            public ExecutionResult execute(
                cn.lypi.contracts.runtime.ExecutionRequest request,
                cn.lypi.contracts.common.ProgressSink progress,
                cn.lypi.contracts.common.AbortSignal signal
            ) {
                return new ExecutionResult(0, "", "", false, Optional.empty());
            }
        };
    }
}
