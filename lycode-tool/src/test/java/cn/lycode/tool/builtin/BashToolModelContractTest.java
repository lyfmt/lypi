package cn.lycode.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import cn.lycode.contracts.runtime.Executor;
import cn.lycode.contracts.tool.ToolDescriptor;
import cn.lycode.tool.DefaultToolRegistry;
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
            public cn.lycode.contracts.runtime.ExecutionResult execute(
                cn.lycode.contracts.runtime.ExecutionRequest request,
                cn.lycode.contracts.common.ProgressSink progress,
                cn.lycode.contracts.common.AbortSignal signal
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
