package cn.lypi.boot;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.boot.headless.HeadlessSubagentCommand;
import cn.lypi.boot.headless.HeadlessSubagentCommandAutoConfiguration;
import cn.lypi.boot.runtime.LyPiRuntimeAutoConfiguration;
import cn.lypi.boot.tool.LyPiToolAutoConfiguration;
import cn.lypi.contracts.agent.TurnRequest;
import cn.lypi.contracts.agent.TurnState;
import cn.lypi.contracts.agent.TurnStatus;
import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContextBudget;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.TextContentBlock;
import cn.lypi.contracts.model.AssistantDone;
import cn.lypi.contracts.model.AssistantEventStream;
import cn.lypi.contracts.model.AssistantStart;
import cn.lypi.contracts.model.AssistantStreamEvent;
import cn.lypi.contracts.model.AssistantStreamResult;
import cn.lypi.contracts.model.ToolCallDelta;
import cn.lypi.contracts.prompt.SystemPrompt;
import cn.lypi.contracts.runtime.AgentCommunicationPort;
import cn.lypi.contracts.runtime.AgentCoreFactoryPort;
import cn.lypi.contracts.runtime.AgentCorePort;
import cn.lypi.contracts.runtime.AiProviderRuntimePort;
import cn.lypi.contracts.runtime.SecurityRuntimePort;
import cn.lypi.contracts.runtime.SessionManagerPort;
import cn.lypi.contracts.runtime.ToolRuntimeInvocation;
import cn.lypi.contracts.runtime.ToolRuntimePort;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionDecisionReason;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.security.PermissionRuntimeState;
import cn.lypi.contracts.session.SessionContext;
import cn.lypi.contracts.subagent.HeadlessSubagentInput;
import cn.lypi.contracts.subagent.HeadlessSubagentOutput;
import cn.lypi.contracts.subagent.MailboxStatus;
import cn.lypi.contracts.subagent.SubagentToolPolicy;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseContext;
import cn.lypi.contracts.tool.ToolUseRequest;
import cn.lypi.runtime.subagent.DefaultMailboxService;
import cn.lypi.runtime.subagent.SubagentProcessHandle;
import cn.lypi.runtime.subagent.SubagentProcessRunner;
import cn.lypi.transport.headless.HeadlessSubagentJsonCodec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;

class SubagentRuntimeEndToEndTest {
    private static final String PARENT_SESSION_ID = "ses_parent";

    @TempDir
    Path tempDir;

    @Test
    void registeredSpawnAndWaitToolsRoundTripThroughHeadlessRunnerExactlyOnce() {
        CapturingChildAgentCoreFactory childFactory = new CapturingChildAgentCoreFactory();
        RecordingParentAiProvider parentAi = new RecordingParentAiProvider();

        contextRunner(childFactory, parentAi).run(context -> {
            SessionManagerPort sessions = context.getBean(SessionManagerPort.class);
            String parentEntryId = prepareParentSession(sessions);
            SessionContext parentContext = sessions.context(parentEntryId);
            PermissionRuntimeState parentPermissions = parentContext.permissionRuntimeState();
            ToolRuntimePort tools = context.getBean(ToolRuntimePort.class);

            ToolResult<?> spawn = executeTool(
                tools,
                sessions,
                "turn_spawn",
                parentEntryId,
                new ToolUseRequest(
                    "toolu_spawn",
                    "spawn_agent",
                    Map.of("task_name", "inspect-session", "message", "inspect the child session"),
                    "msg_parent_history"
                )
            );
            ToolResult<?> wait = executeTool(
                tools,
                sessions,
                "turn_wait",
                sessions.currentView().leafId(),
                new ToolUseRequest("toolu_wait", "wait_agent", Map.of("timeout_ms", 1_000), "msg_wait")
            );

            assertThat(spawn.isError()).isFalse();
            assertThat(wait.isError()).isFalse();
            String spawnOutput = (String) spawn.output();
            String waitOutput = (String) wait.output();
            assertThat(waitOutput).contains("status: SUCCEEDED", "content:\nchild completed");
            assertThat(field(waitOutput, "taskName")).isEqualTo(field(spawnOutput, "taskName"));
            assertThat(field(waitOutput, "agentId")).isEqualTo(field(spawnOutput, "agentId"));
            assertThat(field(waitOutput, "childSessionId")).isEqualTo(field(spawnOutput, "childSessionId"));
            assertThat(field(waitOutput, "runId")).isEqualTo(field(spawnOutput, "runId"));

            assertThat(childFactory.request.get().userInput()).isEqualTo("inspect the child session");
            assertThat(childFactory.initialContext.get().messages()).isEmpty();
            assertThat(childFactory.initialContext.get().model()).isEqualTo(parentContext.model());
            assertThat(childFactory.initialContext.get().thinkingLevel()).isEqualTo(parentContext.thinkingLevel());
            assertThat(childFactory.cwd.get()).isEqualTo(tempDir.toAbsolutePath().normalize());
            assertThat(childFactory.toolPolicy.get().effectiveTools()).containsExactly("read", "grep", "glob");
            assertThat(childFactory.initialContext.get().permissionRuntimeState().mode()).isEqualTo(PermissionMode.AUTO);
            assertThat(childFactory.initialContext.get().permissionRuntimeState().activePermissionProfile())
                .isEqualTo(parentPermissions.activePermissionProfile());
            assertThat(childFactory.initialContext.get().permissionRuntimeState().permissionProfile())
                .isEqualTo(parentPermissions.permissionProfile());

            DefaultMailboxService mailbox = context.getBean(DefaultMailboxService.class);
            assertThat(mailbox.read(PARENT_SESSION_ID, Set.of(MailboxStatus.PENDING))).isEmpty();
            assertThat(mailbox.read(PARENT_SESSION_ID, Set.of(MailboxStatus.DELIVERED))).hasSize(1);
            assertThat(context.getBean(AgentCommunicationPort.class).poll(PARENT_SESSION_ID)).isEmpty();
        });
    }

    @Test
    void completionWithoutWaitIsInjectedAtNextParentModelBoundaryAsSystemLocal() {
        CapturingChildAgentCoreFactory childFactory = new CapturingChildAgentCoreFactory();
        RecordingParentAiProvider parentAi = new RecordingParentAiProvider();

        contextRunner(childFactory, parentAi).run(context -> {
            SessionManagerPort sessions = context.getBean(SessionManagerPort.class);
            String parentEntryId = prepareParentSession(sessions);
            ToolRuntimePort tools = context.getBean(ToolRuntimePort.class);
            ToolResult<?> spawn = executeTool(
                tools,
                sessions,
                "turn_spawn",
                parentEntryId,
                new ToolUseRequest(
                    "toolu_spawn",
                    "spawn_agent",
                    Map.of("task_name", "inspect-session", "message", "inspect without waiting"),
                    "msg_parent_history"
                )
            );
            String spawnOutput = (String) spawn.output();
            DefaultMailboxService mailbox = context.getBean(DefaultMailboxService.class);

            assertThat(spawn.isError()).isFalse();
            assertThat(mailbox.read(PARENT_SESSION_ID, Set.of(MailboxStatus.PENDING))).hasSize(1);

            TurnState state = context.getBean(AgentCorePort.class).execute(new TurnRequest(
                PARENT_SESSION_ID,
                "use the child result",
                Optional.empty(),
                () -> false
            ));

            assertThat(state.status()).isEqualTo(TurnStatus.COMPLETED);
            AgentMessage communication = parentAi.context.get().messages().stream()
                .filter(message -> message.role() == MessageRole.SYSTEM_LOCAL)
                .findFirst()
                .orElseThrow();
            assertThat(communication.role()).isNotEqualTo(MessageRole.USER);
            assertThat(communication.content()).singleElement().isInstanceOfSatisfying(
                TextContentBlock.class,
                block -> {
                    assertThat(block.text()).isEqualTo("child completed");
                    assertThat(block.metadata())
                        .containsEntry("taskName", "inspect-session")
                        .containsEntry("agentId", field(spawnOutput, "agentId"))
                        .containsEntry("childSessionId", field(spawnOutput, "childSessionId"))
                        .containsEntry("runId", field(spawnOutput, "runId"))
                        .containsEntry("status", "SUCCEEDED");
                }
            );
            assertThat(mailbox.read(PARENT_SESSION_ID, Set.of(MailboxStatus.PENDING))).isEmpty();
            assertThat(mailbox.read(PARENT_SESSION_ID, Set.of(MailboxStatus.DELIVERED))).hasSize(1);
            assertThat(context.getBean(AgentCommunicationPort.class).poll(PARENT_SESSION_ID)).isEmpty();
        });
    }

    @Test
    void completionDuringParentToolRoundIsInjectedAtNextBoundaryInSameTurn() {
        CapturingChildAgentCoreFactory childFactory = new CapturingChildAgentCoreFactory();
        ActiveTurnParentAiProvider parentAi = new ActiveTurnParentAiProvider();

        contextRunner(childFactory, parentAi).run(context -> {
            SessionManagerPort sessions = context.getBean(SessionManagerPort.class);
            prepareParentSession(sessions);

            TurnState state = context.getBean(AgentCorePort.class).execute(new TurnRequest(
                PARENT_SESSION_ID,
                "delegate the inspection",
                Optional.empty(),
                () -> false
            ));

            assertThat(state.status()).isEqualTo(TurnStatus.COMPLETED);
            assertThat(state.currentToolRound()).isEqualTo(1);
            assertThat(parentAi.contexts).hasSize(2);
            AgentMessage communication = parentAi.contexts.get(1).messages().stream()
                .filter(message -> message.role() == MessageRole.SYSTEM_LOCAL)
                .findFirst()
                .orElseThrow();
            assertThat(communication.role()).isNotEqualTo(MessageRole.USER);
            assertThat(communication.content()).singleElement().isInstanceOfSatisfying(
                TextContentBlock.class,
                block -> {
                    assertThat(block.text()).isEqualTo("child completed");
                    assertThat(block.metadata())
                        .containsEntry("taskName", "inspect-active-turn")
                        .containsEntry("status", "SUCCEEDED")
                        .containsKeys("agentId", "childSessionId", "runId");
                }
            );
            assertThat(childFactory.toolPolicy.get().effectiveTools())
                .containsExactly("read", "grep", "glob", "bash");

            DefaultMailboxService mailbox = context.getBean(DefaultMailboxService.class);
            assertThat(mailbox.read(PARENT_SESSION_ID, Set.of(MailboxStatus.PENDING))).isEmpty();
            assertThat(mailbox.read(PARENT_SESSION_ID, Set.of(MailboxStatus.DELIVERED))).hasSize(1);
            assertThat(context.getBean(AgentCommunicationPort.class).poll(PARENT_SESSION_ID)).isEmpty();
        });
    }

    private ApplicationContextRunner contextRunner(
        CapturingChildAgentCoreFactory childFactory,
        AiProviderRuntimePort parentAi
    ) {
        return new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                LyPiToolAutoConfiguration.class,
                LyPiRuntimeAutoConfiguration.class
            ))
            .withUserConfiguration(HeadlessSubagentCommandAutoConfiguration.class, InProcessHeadlessConfiguration.class)
            .withPropertyValues(
                "lypi.runtime.cwd=" + tempDir,
                "lypi.runtime.session-id=" + PARENT_SESSION_ID,
                "lypi.runtime.permission-mode=BYPASS",
                "lypi.runtime.transport=headless"
            )
            .withBean(AgentCoreFactoryPort.class, () -> childFactory)
            .withBean(AiProviderRuntimePort.class, () -> parentAi)
            .withBean(SecurityRuntimePort.class, () -> SubagentRuntimeEndToEndTest::allowAllSecurity);
    }

    private String prepareParentSession(SessionManagerPort sessions) {
        sessions.openOrCreate(PARENT_SESSION_ID);
        sessions.appendMessage(new AgentMessage(
            "msg_parent_history",
            MessageRole.USER,
            MessageKind.TEXT,
            List.of(new TextContentBlock("parent history must stay out of the child")),
            Instant.EPOCH,
            Optional.empty(),
            Optional.empty()
        ));
        return sessions.currentView().leafId();
    }

    private ToolResult<?> executeTool(
        ToolRuntimePort tools,
        SessionManagerPort sessions,
        String turnId,
        String parentEntryId,
        ToolUseRequest request
    ) {
        return tools.execute(
            List.of(request),
            contextSnapshot(sessions),
            new ToolRuntimeInvocation(PARENT_SESSION_ID, turnId, parentEntryId)
        ).getFirst();
    }

    private ContextSnapshot contextSnapshot(SessionManagerPort sessions) {
        SessionContext context = sessions.context(sessions.currentView().leafId());
        return new ContextSnapshot(
            new SystemPrompt("system", List.of(), "hash"),
            context.messages(),
            context.model(),
            context.thinkingLevel(),
            context.mode(),
            context.permissionRuntimeState(),
            new ContextBudget(0, 0, 0, 0, 0, 0L, 0L, BigDecimal.ZERO)
        );
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

    private static String field(String output, String name) {
        String prefix = name + ": ";
        return output.lines()
            .filter(line -> line.startsWith(prefix))
            .map(line -> line.substring(prefix.length()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Missing field " + name + " in output: " + output));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class InProcessHeadlessConfiguration {
        @Bean
        SubagentProcessRunner inProcessHeadlessProcessRunner(
            HeadlessSubagentCommand command,
            HeadlessSubagentJsonCodec codec
        ) {
            return new InProcessHeadlessRunner(command, codec);
        }
    }

    private static final class InProcessHeadlessRunner implements SubagentProcessRunner {
        private final HeadlessSubagentCommand command;
        private final HeadlessSubagentJsonCodec codec;

        private InProcessHeadlessRunner(HeadlessSubagentCommand command, HeadlessSubagentJsonCodec codec) {
            this.command = command;
            this.codec = codec;
        }

        @Override
        public SubagentProcessHandle start(HeadlessSubagentInput input) {
            CompletableFuture<HeadlessSubagentOutput> completion = new CompletableFuture<>();
            try {
                ByteArrayOutputStream encodedInput = new ByteArrayOutputStream();
                codec.writeInput(input, encodedInput);
                ByteArrayOutputStream encodedOutput = new ByteArrayOutputStream();
                command.run(new ByteArrayInputStream(encodedInput.toByteArray()), encodedOutput);
                completion.complete(codec.readOutput(new ByteArrayInputStream(encodedOutput.toByteArray())));
            } catch (RuntimeException exception) {
                completion.completeExceptionally(exception);
            }
            return new SubagentProcessHandle() {
                @Override
                public CompletableFuture<HeadlessSubagentOutput> completion() {
                    return completion;
                }

                @Override
                public void interrupt() {
                    completion.cancel(true);
                }
            };
        }
    }

    private static final class CapturingChildAgentCoreFactory implements AgentCoreFactoryPort {
        private final AtomicReference<Path> cwd = new AtomicReference<>();
        private final AtomicReference<SessionContext> initialContext = new AtomicReference<>();
        private final AtomicReference<TurnRequest> request = new AtomicReference<>();
        private final AtomicReference<SubagentToolPolicy> toolPolicy = new AtomicReference<>();

        @Override
        public AgentCorePort create(Path cwd, SessionManagerPort sessionManager) {
            return create(cwd, sessionManager, SubagentToolPolicy.empty());
        }

        @Override
        public AgentCorePort create(Path cwd, SessionManagerPort sessionManager, SubagentToolPolicy toolPolicy) {
            this.cwd.set(cwd);
            this.toolPolicy.set(toolPolicy);
            return request -> {
                this.request.set(request);
                initialContext.set(sessionManager.context(sessionManager.currentView().leafId()));
                AgentMessage assistant = new AgentMessage(
                    "msg_child_final",
                    MessageRole.ASSISTANT,
                    MessageKind.TEXT,
                    List.of(new TextContentBlock("child completed")),
                    Instant.EPOCH,
                    Optional.empty(),
                    Optional.empty()
                );
                sessionManager.appendMessage(assistant);
                return new TurnState(
                    "turn_child",
                    request.sessionId(),
                    null,
                    List.of(assistant),
                    0,
                    TurnStatus.COMPLETED
                );
            };
        }
    }

    private static final class RecordingParentAiProvider implements AiProviderRuntimePort {
        private final AtomicReference<ContextSnapshot> context = new AtomicReference<>();

        @Override
        public AssistantEventStream stream(ContextSnapshot context, cn.lypi.contracts.common.AbortSignal signal) {
            this.context.set(context);
            return new ListAssistantEventStream(List.of(
                new AssistantStart("msg_parent_final"),
                new AssistantDone(Optional.empty(), Optional.of("end_turn"))
            ));
        }
    }

    private static final class ActiveTurnParentAiProvider implements AiProviderRuntimePort {
        private final List<ContextSnapshot> contexts = new ArrayList<>();

        @Override
        public AssistantEventStream stream(ContextSnapshot context, cn.lypi.contracts.common.AbortSignal signal) {
            contexts.add(context);
            if (contexts.size() == 1) {
                return new ListAssistantEventStream(List.of(
                    new AssistantStart("msg_parent_spawn_call"),
                    new ToolCallDelta(
                        "toolu_spawn",
                        "spawn_agent",
                        Map.of(
                            "task_name", "inspect-active-turn",
                            "message", "inspect during the parent turn",
                            "tools", List.of("bash", "bash")
                        ),
                        true
                    ),
                    new AssistantDone(Optional.empty(), Optional.of("tool_calls"))
                ));
            }
            return new ListAssistantEventStream(List.of(
                new AssistantStart("msg_parent_after_child"),
                new AssistantDone(Optional.empty(), Optional.of("end_turn"))
            ));
        }
    }

    private static final class ListAssistantEventStream implements AssistantEventStream {
        private final List<AssistantStreamEvent> events;
        private boolean closed;

        private ListAssistantEventStream(List<AssistantStreamEvent> events) {
            this.events = List.copyOf(events);
        }

        @Override
        public Iterator<AssistantStreamEvent> iterator() {
            return events.iterator();
        }

        @Override
        public AssistantStreamResult result() {
            return new AssistantStreamResult(
                "msg_parent_final",
                events,
                Optional.empty(),
                Optional.of("end_turn"),
                !closed,
                false,
                Optional.empty()
            );
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
