package cn.lypi.tool;

import cn.lypi.contracts.common.JsonSchema;
import cn.lypi.contracts.common.ProgressSink;
import cn.lypi.contracts.common.ValidationResult;
import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.TextContentBlock;
import cn.lypi.contracts.context.ToolResultContentBlock;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.prompt.SystemPrompt;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionDecisionReason;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.security.PermissionUpdate;
import cn.lypi.contracts.tool.InterruptBehavior;
import cn.lypi.contracts.tool.Tool;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseContext;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

final class TestTools {
    private TestTools() {
    }

    static Tool<Map<String, Object>, String> echo(
        String name,
        List<String> aliases,
        boolean readOnly,
        boolean concurrencySafe,
        boolean destructive
    ) {
        return new EchoTool(name, aliases, readOnly, concurrencySafe, destructive, Duration.ZERO);
    }

    static Tool<Map<String, Object>, String> throwingFeatures(String name) {
        return new EchoTool(name, List.of(), true, true, false, Duration.ZERO) {
            @Override
            public boolean isReadOnly(Map<String, Object> input) {
                throw new IllegalStateException("feature unavailable");
            }
        };
    }

    static Tool<Map<String, Object>, String> permission(String name, PermissionBehavior behavior) {
        return new EchoTool(name, List.of(), false, false, true, Duration.ZERO) {
            @Override
            public PermissionDecision checkPermissions(Map<String, Object> input, ToolUseContext context) {
                return decision(behavior, "tool permission");
            }
        };
    }

    static Tool<Map<String, Object>, String> throwingExecute(String name) {
        return new EchoTool(name, List.of(), true, true, false, Duration.ZERO) {
            @Override
            public ToolResult<String> execute(Map<String, Object> input, ToolUseContext context, ProgressSink progress) {
                throw new IllegalStateException("boom");
            }
        };
    }

    static Tool<Map<String, Object>, String> countingTool(
        String name,
        InterruptBehavior interruptBehavior,
        AtomicInteger calls
    ) {
        return new EchoTool(name, List.of(), true, true, false, Duration.ZERO) {
            @Override
            public ToolResult<String> execute(Map<String, Object> input, ToolUseContext context, ProgressSink progress) {
                calls.incrementAndGet();
                return super.execute(input, context, progress);
            }

            @Override
            public InterruptBehavior interruptBehavior() {
                return interruptBehavior;
            }
        };
    }

    static ToolResult<String> result(String toolUseId, String text, boolean error) {
        AgentMessage message = new AgentMessage(
            "msg_" + toolUseId,
            MessageRole.TOOL_RESULT,
            MessageKind.TOOL_RESULT,
            List.of(new ToolResultContentBlock(toolUseId, text, error)),
            Instant.EPOCH,
            Optional.empty(),
            Optional.empty()
        );
        return new ToolResult<>(text, error, List.of(message), Optional.empty());
    }

    static ToolUseContext toolContext(PermissionMode permissionMode) {
        return new ToolUseContext(
            "ses_1",
            "msg_1",
            Path.of("/workspace"),
            Map.of("permissionMode", permissionMode)
        );
    }

    static cn.lypi.contracts.context.ContextSnapshot context(PermissionMode permissionMode) {
        return new cn.lypi.contracts.context.ContextSnapshot(
            new SystemPrompt("system", List.of(), "hash"),
            List.of(),
            new ModelSelection("provider", "model", ThinkingLevel.MEDIUM),
            ThinkingLevel.MEDIUM,
            AgentMode.EXECUTE,
            permissionMode,
            new cn.lypi.contracts.context.ContextBudget(
                0,
                0,
                0,
                0,
                0,
                0L,
                0L,
                BigDecimal.ZERO
            )
        );
    }

    static PermissionDecision decision(PermissionBehavior behavior, String message) {
        return new PermissionDecision(
            behavior,
            PermissionDecisionReason.TOOL_SPECIFIC,
            message,
            Optional.<PermissionUpdate>empty(),
            Map.of()
        );
    }

    static final class BlockingEchoTool extends EchoTool {
        BlockingEchoTool(String name, Duration delay, boolean readOnly, boolean concurrencySafe) {
            super(name, List.of(), readOnly, concurrencySafe, false, delay);
        }
    }

    static final class ObservedConcurrencyTool extends EchoTool {
        private final AtomicInteger active;
        private final AtomicInteger maxActive;

        ObservedConcurrencyTool(String name, AtomicInteger active, AtomicInteger maxActive) {
            super(name, List.of(), true, true, false, Duration.ofMillis(80));
            this.active = active;
            this.maxActive = maxActive;
        }

        @Override
        public ToolResult<String> execute(Map<String, Object> input, ToolUseContext context, ProgressSink progress) {
            int current = active.incrementAndGet();
            maxActive.updateAndGet(previous -> Math.max(previous, current));
            try {
                return super.execute(input, context, progress);
            } finally {
                active.decrementAndGet();
            }
        }
    }

    private static class EchoTool implements Tool<Map<String, Object>, String> {
        private final String name;
        private final List<String> aliases;
        private final boolean readOnly;
        private final boolean concurrencySafe;
        private final boolean destructive;
        private final Duration delay;

        EchoTool(
            String name,
            List<String> aliases,
            boolean readOnly,
            boolean concurrencySafe,
            boolean destructive,
            Duration delay
        ) {
            this.name = name;
            this.aliases = List.copyOf(aliases);
            this.readOnly = readOnly;
            this.concurrencySafe = concurrencySafe;
            this.destructive = destructive;
            this.delay = delay;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public List<String> aliases() {
            return aliases;
        }

        @Override
        public JsonSchema inputSchema() {
            return new JsonSchema(Map.of());
        }

        @Override
        public ValidationResult validateInput(Map<String, Object> input, ToolUseContext context) {
            return new ValidationResult(true, List.of());
        }

        @Override
        public PermissionDecision checkPermissions(Map<String, Object> input, ToolUseContext context) {
            return decision(PermissionBehavior.ALLOW, "allowed");
        }

        @Override
        public ToolResult<String> execute(Map<String, Object> input, ToolUseContext context, ProgressSink progress) {
            if (!delay.isZero()) {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return result("interrupted", "interrupted", true);
                }
            }
            Object text = input == null ? null : input.get("text");
            Object toolUseId = context.metadata().get("toolUseId");
            return result(toolUseId == null ? "toolu_1" : toolUseId.toString(), text == null ? "" : text.toString(), false);
        }

        @Override
        public InterruptBehavior interruptBehavior() {
            return InterruptBehavior.CANCEL;
        }

        @Override
        public boolean isReadOnly(Map<String, Object> input) {
            return readOnly;
        }

        @Override
        public boolean isConcurrencySafe(Map<String, Object> input) {
            return concurrencySafe;
        }

        @Override
        public boolean isDestructive(Map<String, Object> input) {
            return destructive;
        }

        @Override
        public int maxResultSize() {
            return 4096;
        }

        @Override
        public String renderForUser(Map<String, Object> input) {
            return name + " " + input;
        }

        @Override
        public AgentMessage serializeForContext(String output) {
            return new AgentMessage(
                "msg_" + name,
                MessageRole.TOOL_RESULT,
                MessageKind.TOOL_RESULT,
                List.of(new TextContentBlock(output)),
                Instant.EPOCH,
                Optional.empty(),
                Optional.empty()
            );
        }
    }
}
