package cn.lypi.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import cn.lypi.contracts.runtime.Executor;
import cn.lypi.contracts.tool.ToolDescriptor;
import cn.lypi.tool.DefaultToolRegistry;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BashToolModelContractTest {
    @Test
    void exposesOnlyPublicBashCapabilityAndInputs() {
        Executor unusedExecutor = new Executor() {
            @Override
            public String name() {
                return "unused";
            }

            @Override
            public cn.lypi.contracts.runtime.ExecutionResult execute(
                cn.lypi.contracts.runtime.ExecutionRequest request,
                cn.lypi.contracts.common.ProgressSink progress,
                cn.lypi.contracts.common.AbortSignal signal
            ) {
                throw new AssertionError("model contract test must not execute bash");
            }
        };
        DefaultToolRegistry registry = new DefaultToolRegistry();
        registry.register(new BashTool(unusedExecutor));

        ToolDescriptor descriptor = registry.snapshot().tools().getFirst();
        assertEquals("Execute shell commands.", descriptor.description());
        String normalizedDescription = descriptor.description().toLowerCase(Locale.ROOT);
        for (String implementationTerm : new String[] {
            "cwd", "working directory", "persistent", "snapshot", "export", "source"
        }) {
            assertFalse(
                normalizedDescription.contains(implementationTerm),
                () -> "model-visible description contains shell-state implementation term: " + implementationTerm
            );
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) descriptor.inputSchema().value().get("properties");
        assertEquals(Map.of("type", "string"), properties.get("command"));
        assertFalse(properties.containsKey("cwd"));
    }
}
