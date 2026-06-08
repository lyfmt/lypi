package cn.lypi.boot.tool;

import cn.lypi.contracts.common.ProgressSink;
import cn.lypi.contracts.common.ValidationResult;
import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContextBudget;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.ToolResultContentBlock;
import cn.lypi.contracts.event.AgentEvent;
import cn.lypi.contracts.event.EventBus;
import cn.lypi.contracts.event.EventConsumer;
import cn.lypi.contracts.event.EventFilter;
import cn.lypi.contracts.event.EventSubscription;
import cn.lypi.contracts.event.PermissionDecisionEvent;
import cn.lypi.contracts.event.PermissionRequestEvent;
import cn.lypi.contracts.event.ToolEndEvent;
import cn.lypi.contracts.event.ToolProgressEvent;
import cn.lypi.contracts.event.ToolStartEvent;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.prompt.SystemPrompt;
import cn.lypi.contracts.runtime.SecurityRuntimePort;
import cn.lypi.contracts.runtime.AgentCenterPort;
import cn.lypi.contracts.runtime.Executor;
import cn.lypi.contracts.runtime.MailboxPort;
import cn.lypi.contracts.runtime.ToolRuntimePort;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionDecisionReason;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.common.ToolProgress;
import cn.lypi.contracts.tool.Tool;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseContext;
import cn.lypi.contracts.tool.ToolUseRequest;
import cn.lypi.contracts.subagent.HeadlessSubagentOutput;
import cn.lypi.contracts.subagent.MailboxCommandResult;
import cn.lypi.contracts.subagent.MailboxMessage;
import cn.lypi.contracts.subagent.MailboxStatus;
import cn.lypi.contracts.subagent.SubagentSpawnRequest;
import cn.lypi.contracts.subagent.SubagentSpawnResult;
import cn.lypi.tool.PermissionGateResult;
import cn.lypi.tool.PermissionPromptPort;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class LyPiToolAutoConfigurationTest {
    @Test
    void createsHostExecutorAndRegistersDefaultTools() {
        new ApplicationContextRunner()
            .withUserConfiguration(LyPiToolAutoConfiguration.class)
            .withBean(SecurityRuntimePort.class, () -> LyPiToolAutoConfigurationTest::allowAllSecurity)
            .run(context -> {
                assertThat(context).hasSingleBean(Executor.class);
                assertThat(context.getBean(Executor.class).name()).isEqualTo("host");

                ToolRuntimePort runtime = context.getBean(ToolRuntimePort.class);
                assertThat(runtime.resolve("bash")).isPresent();
                assertThat(runtime.resolve("read")).isPresent();
                assertThat(runtime.resolve("glob")).isPresent();
            });
    }

    @Test
    void keepsUserProvidedExecutor() {
        Executor customExecutor = new Executor() {
            @Override
            public String name() {
                return "custom";
            }

            @Override
            public cn.lypi.contracts.runtime.ExecutionResult execute(
                cn.lypi.contracts.runtime.ExecutionRequest request,
                ProgressSink progress,
                cn.lypi.contracts.common.AbortSignal signal
            ) {
                return new cn.lypi.contracts.runtime.ExecutionResult(0, "", "", false, Optional.empty());
            }
        };

        new ApplicationContextRunner()
            .withUserConfiguration(LyPiToolAutoConfiguration.class)
            .withBean(SecurityRuntimePort.class, () -> LyPiToolAutoConfigurationTest::allowAllSecurity)
            .withBean(Executor.class, () -> customExecutor)
            .run(context -> assertThat(context.getBean(Executor.class)).isSameAs(customExecutor));
    }

    @Test
    void createsInteractiveRuntimeWhenPromptPortIsAvailable() {
        RecordingEventBus eventBus = new RecordingEventBus();
        AtomicReference<PermissionPromptPort.Handle> promptHandle = new AtomicReference<>();
        PermissionPromptPort promptPort = handle -> {
            promptHandle.set(handle);
            return PermissionGateResult.deny("用户拒绝");
        };

        new ApplicationContextRunner()
            .withUserConfiguration(LyPiToolAutoConfiguration.class)
            .withBean(EventBus.class, () -> eventBus)
            .withBean(SecurityRuntimePort.class, () -> LyPiToolAutoConfigurationTest::allowAllSecurity)
            .withBean(PermissionPromptPort.class, () -> promptPort)
            .run(context -> {
                assertThat(context).hasSingleBean(ToolRuntimePort.class);
                ToolRuntimePort runtime = context.getBean(ToolRuntimePort.class);
                runtime.register(new AskTool());

                ToolResult<?> result = runtime.execute(
                    List.of(new ToolUseRequest("toolu_1", "ask-test", Map.of("text", "ignored"), "msg_1")),
                    context()
                ).getFirst();

                assertThat(result.isError()).isTrue();
                assertThat(((ToolResultContentBlock) result.newMessages().getFirst().content().getFirst()).text())
                    .contains("用户拒绝");
                assertThat(promptHandle.get()).isNotNull();
                assertThat(promptHandle.get().request().toolUseId()).isEqualTo("toolu_1");
                assertThat(eventBus.events).extracting(Object::getClass)
                    .containsExactly(
                        ToolStartEvent.class,
                        PermissionRequestEvent.class,
                        PermissionDecisionEvent.class,
                        ToolEndEvent.class
                    );
                ToolEndEvent end = (ToolEndEvent) eventBus.events.get(3);
                assertThat(end.error()).isTrue();
                assertThat(end.resultSummary().summary()).contains("用户拒绝");
            });
    }

    @Test
    void createsHeadlessDenyRuntimeWhenPromptPortIsMissing() {
        new ApplicationContextRunner()
            .withUserConfiguration(LyPiToolAutoConfiguration.class)
            .withBean(SecurityRuntimePort.class, () -> LyPiToolAutoConfigurationTest::allowAllSecurity)
            .run(context -> {
                assertThat(context).hasSingleBean(ToolRuntimePort.class);
                ToolRuntimePort runtime = context.getBean(ToolRuntimePort.class);
                runtime.register(new AskTool());

                ToolResult<?> result = runtime.execute(
                    List.of(new ToolUseRequest("toolu_1", "ask-test", Map.of("text", "ignored"), "msg_1")),
                    context()
                ).getFirst();

                assertThat(result.isError()).isTrue();
                assertThat(((ToolResultContentBlock) result.newMessages().getFirst().content().getFirst()).text())
                    .contains("权限请求未获允许");
            });
    }

    @Test
    void defaultRuntimePublishesToolLifecycleAndProgressWhenEventBusIsAvailable() {
        RecordingEventBus eventBus = new RecordingEventBus();

        new ApplicationContextRunner()
            .withUserConfiguration(LyPiToolAutoConfiguration.class)
            .withBean(EventBus.class, () -> eventBus)
            .withBean(SecurityRuntimePort.class, () -> LyPiToolAutoConfigurationTest::allowAllSecurity)
            .run(context -> {
                ToolRuntimePort runtime = context.getBean(ToolRuntimePort.class);
                runtime.register(new ProgressTool());

                ToolResult<?> result = runtime.execute(
                    List.of(new ToolUseRequest("toolu_1", "progress-test", Map.of("text", "done"), "msg_1")),
                    context()
                ).getFirst();

                assertThat(result.isError()).isFalse();
                assertThat(eventBus.events).extracting(Object::getClass)
                    .containsExactly(ToolStartEvent.class, ToolProgressEvent.class, ToolEndEvent.class);
                ToolStartEvent start = (ToolStartEvent) eventBus.events.get(0);
                assertThat(start.toolUseId()).isEqualTo("toolu_1");
                assertThat(start.parentMessageId()).isEqualTo("msg_1");
                ToolProgressEvent progress = (ToolProgressEvent) eventBus.events.get(1);
                assertThat(progress.toolUseId()).isEqualTo("toolu_1");
                assertThat(progress.progress().title()).isEqualTo("working");
                ToolEndEvent end = (ToolEndEvent) eventBus.events.get(2);
                assertThat(end.toolUseId()).isEqualTo("toolu_1");
                assertThat(end.error()).isFalse();
                assertThat(end.durationMillis()).isGreaterThanOrEqualTo(0L);
            });
    }

    @Test
    void registersSubagentToolsWhenRuntimePortsAreAvailable() {
        new ApplicationContextRunner()
            .withUserConfiguration(LyPiToolAutoConfiguration.class)
            .withBean(SecurityRuntimePort.class, () -> LyPiToolAutoConfigurationTest::allowAllSecurity)
            .withBean(AgentCenterPort.class, LyPiToolAutoConfigurationTest::agentCenter)
            .withBean(MailboxPort.class, LyPiToolAutoConfigurationTest::mailbox)
            .run(context -> {
                ToolRuntimePort runtime = context.getBean(ToolRuntimePort.class);

                assertThat(runtime.resolve("spawn_agent")).isPresent();
                assertThat(runtime.resolve("read_agent_result")).isPresent();
                assertThat(runtime.resolve("read_mailbox")).isPresent();
            });
    }

    private static PermissionDecision allowAllSecurity(ToolUseRequest request, ToolUseContext context) {
        return new PermissionDecision(
            PermissionBehavior.ALLOW,
            PermissionDecisionReason.MODE_DEFAULT,
            "allowed",
            Optional.empty(),
            Map.of()
        );
    }

    private static ContextSnapshot context() {
        return new ContextSnapshot(
            new SystemPrompt("system", List.of(), "hash"),
            List.of(),
            new ModelSelection("provider", "model", ThinkingLevel.MEDIUM),
            ThinkingLevel.MEDIUM,
            AgentMode.EXECUTE,
            PermissionMode.DEFAULT_EXECUTE,
            new ContextBudget(0, 0, 0, 0, 0, 0L, 0L, BigDecimal.ZERO)
        );
    }

    private static AgentCenterPort agentCenter() {
        return new AgentCenterPort() {
            @Override
            public SubagentSpawnResult spawn(SubagentSpawnRequest request) {
                throw new UnsupportedOperationException("not used");
            }

            @Override
            public MailboxCommandResult interrupt(String agentId) {
                throw new UnsupportedOperationException("not used");
            }

            @Override
            public Optional<HeadlessSubagentOutput> readResult(String childSessionId) {
                throw new UnsupportedOperationException("not used");
            }
        };
    }

    private static MailboxPort mailbox() {
        return new MailboxPort() {
            @Override
            public List<MailboxMessage> read(String sessionId, java.util.Set<MailboxStatus> statuses) {
                throw new UnsupportedOperationException("not used");
            }

            @Override
            public MailboxCommandResult accept(String sessionId, String mailId) {
                throw new UnsupportedOperationException("not used");
            }

            @Override
            public MailboxCommandResult stash(String sessionId, String mailId) {
                throw new UnsupportedOperationException("not used");
            }

            @Override
            public MailboxCommandResult discard(String sessionId, String mailId) {
                throw new UnsupportedOperationException("not used");
            }
        };
    }

    private static final class RecordingEventBus implements EventBus {
        private final List<AgentEvent> events = new ArrayList<>();

        @Override
        public void publish(AgentEvent event) {
            events.add(event);
        }

        @Override
        public EventSubscription subscribe(EventFilter filter, EventConsumer consumer) {
            return () -> {
            };
        }
    }

    private static final class AskTool implements Tool<Map<String, Object>, String> {
        @Override
        public String name() {
            return "ask-test";
        }

        @Override
        public PermissionDecision checkPermissions(Map<String, Object> input, ToolUseContext context) {
            return new PermissionDecision(
                PermissionBehavior.ASK,
                PermissionDecisionReason.TOOL_SPECIFIC,
                "需要确认",
                Optional.empty(),
                Map.of()
            );
        }

        @Override
        public ValidationResult validateInput(Map<String, Object> input, ToolUseContext context) {
            return new ValidationResult(true, List.of());
        }

        @Override
        public ToolResult<String> execute(Map<String, Object> input, ToolUseContext context, ProgressSink progress) {
            throw new AssertionError("ASK deny must prevent execution");
        }

        @Override
        public List<String> aliases() {
            return List.of();
        }

        @Override
        public cn.lypi.contracts.common.JsonSchema inputSchema() {
            return new cn.lypi.contracts.common.JsonSchema(Map.of());
        }

        @Override
        public cn.lypi.contracts.tool.InterruptBehavior interruptBehavior() {
            return cn.lypi.contracts.tool.InterruptBehavior.CANCEL;
        }

        @Override
        public boolean isReadOnly(Map<String, Object> input) {
            return false;
        }

        @Override
        public boolean isConcurrencySafe(Map<String, Object> input) {
            return false;
        }

        @Override
        public boolean isDestructive(Map<String, Object> input) {
            return true;
        }

        @Override
        public int maxResultSize() {
            return 4096;
        }

        @Override
        public String renderForUser(Map<String, Object> input) {
            return "write " + input;
        }

        @Override
        public AgentMessage serializeForContext(String output) {
            return new AgentMessage(
                "msg_tool_result",
                MessageRole.TOOL_RESULT,
                MessageKind.TOOL_RESULT,
                List.of(new ToolResultContentBlock("toolu_1", output, false)),
                Instant.EPOCH,
                Optional.empty(),
                Optional.empty()
            );
        }
    }

    private static final class ProgressTool implements Tool<Map<String, Object>, String> {
        @Override
        public String name() {
            return "progress-test";
        }

        @Override
        public PermissionDecision checkPermissions(Map<String, Object> input, ToolUseContext context) {
            return new PermissionDecision(
                PermissionBehavior.ALLOW,
                PermissionDecisionReason.TOOL_SPECIFIC,
                "允许",
                Optional.empty(),
                Map.of()
            );
        }

        @Override
        public ValidationResult validateInput(Map<String, Object> input, ToolUseContext context) {
            return new ValidationResult(true, List.of());
        }

        @Override
        public ToolResult<String> execute(Map<String, Object> input, ToolUseContext context, ProgressSink progress) {
            progress.progress(ToolProgress.status("working", "running"));
            String output = String.valueOf(input.get("text"));
            return new ToolResult<>(output, false, List.of(serializeForContext(output)), Optional.empty());
        }

        @Override
        public List<String> aliases() {
            return List.of();
        }

        @Override
        public cn.lypi.contracts.common.JsonSchema inputSchema() {
            return new cn.lypi.contracts.common.JsonSchema(Map.of());
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
            return 4096;
        }

        @Override
        public String renderForUser(Map<String, Object> input) {
            return "progress " + input;
        }

        @Override
        public AgentMessage serializeForContext(String output) {
            return new AgentMessage(
                "msg_tool_result",
                MessageRole.TOOL_RESULT,
                MessageKind.TOOL_RESULT,
                List.of(new ToolResultContentBlock("toolu_1", output, false)),
                Instant.EPOCH,
                Optional.empty(),
                Optional.empty()
            );
        }
    }
}
