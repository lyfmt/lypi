package cn.lypi.runtime.subagent;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.runtime.ToolRuntimeInvocation;
import cn.lypi.contracts.runtime.ToolRuntimePort;
import cn.lypi.contracts.subagent.SubagentToolPolicy;
import cn.lypi.contracts.tool.Tool;
import cn.lypi.contracts.tool.ToolDescriptor;
import cn.lypi.contracts.tool.ToolRegistrySnapshot;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseRequest;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SubagentToolPolicyNormalizerTest {
    @Test
    void addsBaseReadToolsAndDeduplicatesModelTools() {
        SubagentToolPolicy policy = SubagentToolPolicyNormalizer.normalize(
            List.of("read", "read", "grep", "bash"),
            runtime(Map.of("read", "read", "grep", "grep", "glob", "glob", "bash", "bash"))
        );

        assertThat(policy.requestedTools()).containsExactly("read", "grep", "bash");
        assertThat(policy.effectiveTools()).containsExactly("read", "grep", "glob", "bash");
    }

    @Test
    void canonicalizesAliasesThroughRuntimeResolve() {
        SubagentToolPolicy policy = SubagentToolPolicyNormalizer.normalize(
            List.of("cat", "sh", "cat"),
            runtime(Map.of("read", "read", "grep", "grep", "glob", "glob", "cat", "read", "sh", "bash"))
        );

        assertThat(policy.requestedTools()).containsExactly("read", "bash");
        assertThat(policy.effectiveTools()).containsExactly("read", "grep", "glob", "bash");
    }

    @Test
    void keepsUnknownToolNameForLaterRuntimeDiagnostics() {
        SubagentToolPolicy policy = SubagentToolPolicyNormalizer.normalize(
            List.of("unknown_tool"),
            runtime(Map.of("read", "read", "grep", "grep", "glob", "glob"))
        );

        assertThat(policy.requestedTools()).containsExactly("unknown_tool");
        assertThat(policy.effectiveTools()).containsExactly("read", "grep", "glob", "unknown_tool");
    }

    private static ToolRuntimePort runtime(Map<String, String> canonicalByNameOrAlias) {
        return new ToolRuntimePort() {
            @Override
            public void register(Tool<?, ?> tool) {
            }

            @Override
            public Optional<Tool<?, ?>> resolve(String nameOrAlias) {
                String canonical = canonicalByNameOrAlias.get(nameOrAlias);
                if (canonical == null) {
                    return Optional.empty();
                }
                return Optional.of(new NamedTool(canonical));
            }

            @Override
            public ToolRegistrySnapshot snapshot() {
                return new ToolRegistrySnapshot(canonicalByNameOrAlias.values().stream()
                    .distinct()
                    .map(name -> new ToolDescriptor(name, List.of(), true, false))
                    .toList());
            }

            @Override
            public Path cwd() {
                return Path.of(".");
            }

            @Override
            public List<ToolResult<?>> execute(List<ToolUseRequest> requests, ContextSnapshot context) {
                return List.of();
            }

            @Override
            public List<ToolResult<?>> execute(
                List<ToolUseRequest> requests,
                ContextSnapshot context,
                ToolRuntimeInvocation invocation
            ) {
                return List.of();
            }
        };
    }

    private record NamedTool(String name) implements Tool<Map<String, Object>, String> {
        @Override
        public List<String> aliases() {
            return List.of();
        }

        @Override
        public cn.lypi.contracts.common.JsonSchema inputSchema() {
            return new cn.lypi.contracts.common.JsonSchema(Map.of());
        }

        @Override
        public cn.lypi.contracts.common.ValidationResult validateInput(
            Map<String, Object> input,
            cn.lypi.contracts.tool.ToolUseContext context
        ) {
            return new cn.lypi.contracts.common.ValidationResult(true, List.of());
        }

        @Override
        public cn.lypi.contracts.security.PermissionDecision checkPermissions(
            Map<String, Object> input,
            cn.lypi.contracts.tool.ToolUseContext context
        ) {
            return null;
        }

        @Override
        public ToolResult<String> execute(
            Map<String, Object> input,
            cn.lypi.contracts.tool.ToolUseContext context,
            cn.lypi.contracts.common.ProgressSink progress
        ) {
            return null;
        }

        @Override
        public cn.lypi.contracts.tool.InterruptBehavior interruptBehavior() {
            return cn.lypi.contracts.tool.InterruptBehavior.CANCEL;
        }

        @Override
        public boolean isReadOnly(Map<String, Object> input) {
            return true;
        }

        @Override
        public boolean isConcurrencySafe(Map<String, Object> input) {
            return true;
        }

        @Override
        public boolean isDestructive(Map<String, Object> input) {
            return false;
        }

        @Override
        public int maxResultSize() {
            return 1024;
        }

        @Override
        public String renderForUser(Map<String, Object> input) {
            return name;
        }

        @Override
        public cn.lypi.contracts.context.AgentMessage serializeForContext(String output) {
            return null;
        }
    }
}
