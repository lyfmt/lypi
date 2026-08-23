package cn.lycode.boot;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lycode.boot.headless.HeadlessSubagentCommand;
import cn.lycode.boot.headless.HeadlessSubagentCommandAutoConfiguration;
import cn.lycode.boot.runtime.LyCodeRuntimeAutoConfiguration;
import cn.lycode.boot.tool.LyCodeToolAutoConfiguration;
import cn.lycode.contracts.agent.SteeringMessage;
import cn.lycode.contracts.agent.SteeringMessageSource;
import cn.lycode.contracts.agent.TurnRequest;
import cn.lycode.contracts.agent.TurnState;
import cn.lycode.contracts.agent.TurnStatus;
import cn.lycode.contracts.context.AgentMessage;
import cn.lycode.contracts.context.ContentBlock;
import cn.lycode.contracts.context.ContextBudget;
import cn.lycode.contracts.context.ContextSnapshot;
import cn.lycode.contracts.context.MessageKind;
import cn.lycode.contracts.context.MessageRole;
import cn.lycode.contracts.context.TextContentBlock;
import cn.lycode.contracts.context.ToolCallContentBlock;
import cn.lycode.contracts.context.ToolResultContentBlock;
import cn.lycode.contracts.event.EventBus;
import cn.lycode.contracts.event.EventFilter;
import cn.lycode.contracts.event.EventSubscription;
import cn.lycode.contracts.event.MessageEndEvent;
import cn.lycode.contracts.event.ToolProgressEvent;
import cn.lycode.contracts.common.SignalSubscription;
import cn.lycode.contracts.model.ApiStyle;
import cn.lycode.contracts.model.AssistantDone;
import cn.lycode.contracts.model.AssistantEventStream;
import cn.lycode.contracts.model.AssistantStart;
import cn.lycode.contracts.model.AssistantStreamEvent;
import cn.lycode.contracts.model.AssistantStreamResult;
import cn.lycode.contracts.model.CostProfile;
import cn.lycode.contracts.model.ModelCatalogPort;
import cn.lycode.contracts.model.ModelDescriptor;
import cn.lycode.contracts.model.ToolCallDelta;
import cn.lycode.contracts.prompt.SystemPrompt;
import cn.lycode.contracts.runtime.AgentCommunicationPort;
import cn.lycode.contracts.runtime.AgentCoreFactoryPort;
import cn.lycode.contracts.runtime.AgentCorePort;
import cn.lycode.contracts.runtime.AiProviderRuntimePort;
import cn.lycode.contracts.runtime.SecurityRuntimePort;
import cn.lycode.contracts.runtime.SessionManagerPort;
import cn.lycode.contracts.runtime.ToolRuntimeInvocation;
import cn.lycode.contracts.runtime.ToolRuntimePort;
import cn.lycode.contracts.security.PermissionBehavior;
import cn.lycode.contracts.security.PermissionDecision;
import cn.lycode.contracts.security.PermissionDecisionReason;
import cn.lycode.contracts.security.PermissionMode;
import cn.lycode.contracts.security.PermissionRuntimeState;
import cn.lycode.contracts.session.SessionContext;
import cn.lycode.contracts.subagent.HeadlessSubagentInput;
import cn.lycode.contracts.subagent.HeadlessSubagentOutput;
import cn.lycode.contracts.subagent.MailboxStatus;
import cn.lycode.contracts.subagent.SubagentRunStatus;
import cn.lycode.contracts.subagent.SubagentToolPolicy;
import cn.lycode.contracts.tool.ToolResult;
import cn.lycode.contracts.tool.ToolUseContext;
import cn.lycode.contracts.tool.ToolUseRequest;
import cn.lycode.runtime.subagent.DefaultMailboxService;
import cn.lycode.runtime.subagent.SubagentProcessHandle;
import cn.lycode.runtime.subagent.SubagentProcessRunner;
import cn.lycode.session.SessionTreeQuery;
import cn.lycode.transport.headless.HeadlessSubagentJsonCodec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
    void configuredExpertAgentFlowsFromYamlIntoChildSession() throws Exception {
        Path agentDirectory = tempDir.resolve(".ly-code").resolve("agents");
        Files.createDirectories(agentDirectory);
        Files.writeString(agentDirectory.resolve("code-reviewer.yaml"), """
            name: code-reviewer
            provider: expert-provider
            model: expert-model
            prompt: |
              Review code precisely.
              Report concrete findings only.
            tools:
              - bash
            """);
        CapturingChildAgentCoreFactory childFactory = new CapturingChildAgentCoreFactory();
        RecordingParentAiProvider parentAi = new RecordingParentAiProvider();
        ModelCatalogPort modelCatalog = selection -> {
            if (!"expert-provider".equals(selection.provider())
                || !"expert-model-override".equals(selection.modelId())) {
                return Optional.empty();
            }
            return Optional.of(new ModelDescriptor(
                selection.provider(),
                selection.modelId(),
                URI.create("https://example.invalid"),
                ApiStyle.CUSTOM,
                128_000,
                8_192,
                true,
                false,
                new CostProfile(BigDecimal.ZERO, BigDecimal.ZERO, "USD"),
                Map.of()
            ));
        };

        contextRunner(childFactory, parentAi)
            .withBean(ModelCatalogPort.class, () -> modelCatalog)
            .run(context -> {
                SessionManagerPort sessions = context.getBean(SessionManagerPort.class);
                String parentEntryId = prepareParentSession(sessions);

                ToolResult<?> spawn = executeTool(
                    context.getBean(ToolRuntimePort.class),
                    sessions,
                    "turn_expert_spawn",
                    parentEntryId,
                    new ToolUseRequest(
                        "toolu_expert_spawn",
                        "spawn_agent",
                        Map.of(
                            "task_name", "review-auth",
                            "message", "Review the authentication changes.",
                            "agent", "code-reviewer",
                            "model", "expert-model-override",
                            "tools", List.of()
                        ),
                        "msg_parent_history"
                    )
                );

                assertThat(spawn.isError()).isFalse();
                assertThat(childFactory.request.get().userInput())
                    .isEqualTo("Review the authentication changes.");
                assertThat(childFactory.initialContext.get().messages()).singleElement().satisfies(message -> {
                    assertThat(message.role()).isEqualTo(MessageRole.SYSTEM_LOCAL);
                    assertThat(message.content().getFirst().text())
                        .isEqualTo("Review code precisely.\nReport concrete findings only.");
                });
                assertThat(childFactory.initialContext.get().model().provider()).isEqualTo("expert-provider");
                assertThat(childFactory.initialContext.get().model().modelId()).isEqualTo("expert-model-override");
                assertThat(childFactory.toolPolicy.get().effectiveTools()).containsExactly("read", "grep", "glob");
                assertThat(new SessionTreeQuery(tempDir).children(PARENT_SESSION_ID))
                    .singleElement()
                    .satisfies(child -> assertThat(child.agentRole()).contains("code-reviewer"));
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

    @Test
    void completionBetweenWaitCallAndResultPreservesBothToolPairs() {
        CapturingChildAgentCoreFactory childFactory = new CapturingChildAgentCoreFactory();
        ControlledSubagentProcessRunner childProcess = new ControlledSubagentProcessRunner();
        InterleavedWaitParentAiProvider parentAi = new InterleavedWaitParentAiProvider();

        contextRunner(childFactory, parentAi, childProcess).run(context -> {
            SessionManagerPort sessions = context.getBean(SessionManagerPort.class);
            prepareParentSession(sessions);
            EventBus eventBus = context.getBean(EventBus.class);

            try (EventSubscription ignored = eventBus.subscribe(
                new EventFilter(Optional.of(PARENT_SESSION_ID), Optional.of(MessageEndEvent.class)),
                envelope -> {
                    MessageEndEvent event = (MessageEndEvent) envelope.event();
                    if ("msg_parent_wait_call".equals(event.messageId())) {
                        childProcess.completeSuccessfully();
                    }
                }
            )) {
                TurnState state = context.getBean(AgentCorePort.class).execute(new TurnRequest(
                    PARENT_SESSION_ID,
                    "delegate and wait",
                    Optional.empty(),
                    () -> false
                ));

                assertThat(state.status()).isEqualTo(TurnStatus.COMPLETED);
                assertThat(state.currentToolRound()).isEqualTo(2);
            }

            assertThat(childProcess.completed()).isTrue();
            assertThat(parentAi.contexts).hasSize(3);
            assertThat(toolBlockKeys(parentAi.contexts.get(2))).containsExactly(
                "call:toolu_spawn_interleaved",
                "result:toolu_spawn_interleaved",
                "call:toolu_wait_interleaved",
                "result:toolu_wait_interleaved"
            );
        });
    }

    @Test
    void steeringWakesWaitAndIsInjectedAtNextModelBoundaryInSameTurn() {
        CapturingChildAgentCoreFactory childFactory = new CapturingChildAgentCoreFactory();
        WaitThenFinishParentAiProvider parentAi = new WaitThenFinishParentAiProvider();
        TestSteeringSource steering = new TestSteeringSource();

        contextRunner(childFactory, parentAi).run(context -> {
            context.getBean(SessionManagerPort.class).openOrCreate(PARENT_SESSION_ID);
            CountDownLatch waitStarted = waitStartedLatch(context.getBean(EventBus.class));
            TurnRequest request = new TurnRequest(
                PARENT_SESSION_ID,
                "wait for a child",
                Optional.empty(),
                () -> false,
                TurnRequest.DEFAULT_MAX_TOOL_ROUNDS,
                List.of(),
                steering
            );

            try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
                Future<TurnState> turn = executor.submit(() -> context.getBean(AgentCorePort.class).execute(request));
                try {
                    assertThat(waitStarted.await(1, TimeUnit.SECONDS)).isTrue();
                    steering.add(SteeringMessage.user("stop waiting", List.of()));
                    TurnState state = turn.get(2, TimeUnit.SECONDS);

                    assertThat(state.status()).isEqualTo(TurnStatus.COMPLETED);
                    assertThat(parentAi.contexts).hasSize(2);
                    assertThat(waitResultText(parentAi.contexts.get(1))).contains("新的用户输入");
                    assertThat(parentAi.contexts.get(1).messages())
                        .filteredOn(message -> message.role() == MessageRole.USER)
                        .extracting(message -> message.content().getFirst().text())
                        .containsExactly("wait for a child", "stop waiting");
                } finally {
                    turn.cancel(true);
                }
            }
        });
    }

    @Test
    void abortWakesWaitAndEndsTurnWithoutAnotherModelCall() {
        CapturingChildAgentCoreFactory childFactory = new CapturingChildAgentCoreFactory();
        WaitThenFinishParentAiProvider parentAi = new WaitThenFinishParentAiProvider();
        TestAbortSignal abort = new TestAbortSignal();

        contextRunner(childFactory, parentAi).run(context -> {
            context.getBean(SessionManagerPort.class).openOrCreate(PARENT_SESSION_ID);
            CountDownLatch waitStarted = waitStartedLatch(context.getBean(EventBus.class));
            TurnRequest request = new TurnRequest(PARENT_SESSION_ID, "wait for a child", Optional.empty(), abort);

            try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
                Future<TurnState> turn = executor.submit(() -> context.getBean(AgentCorePort.class).execute(request));
                try {
                    assertThat(waitStarted.await(1, TimeUnit.SECONDS)).isTrue();
                    abort.abort();
                    TurnState state = turn.get(2, TimeUnit.SECONDS);

                    assertThat(state.status()).isEqualTo(TurnStatus.ABORTED);
                    assertThat(parentAi.contexts).hasSize(1);
                } finally {
                    turn.cancel(true);
                }
            }
        });
    }

    private ApplicationContextRunner contextRunner(
        CapturingChildAgentCoreFactory childFactory,
        AiProviderRuntimePort parentAi
    ) {
        return contextRunner(childFactory, parentAi, null);
    }

    private ApplicationContextRunner contextRunner(
        CapturingChildAgentCoreFactory childFactory,
        AiProviderRuntimePort parentAi,
        SubagentProcessRunner processRunner
    ) {
        ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                LyCodeToolAutoConfiguration.class,
                LyCodeRuntimeAutoConfiguration.class
            ))
            .withUserConfiguration(HeadlessSubagentCommandAutoConfiguration.class)
            .withPropertyValues(
                "lycode.runtime.cwd=" + tempDir,
                "lycode.runtime.session-id=" + PARENT_SESSION_ID,
                "lycode.runtime.permission-mode=BYPASS",
                "lycode.runtime.transport=headless"
            )
            .withBean(AgentCoreFactoryPort.class, () -> childFactory)
            .withBean(AiProviderRuntimePort.class, () -> parentAi)
            .withBean(SecurityRuntimePort.class, () -> SubagentRuntimeEndToEndTest::allowAllSecurity);
        return processRunner == null
            ? runner.withUserConfiguration(InProcessHeadlessConfiguration.class)
            : runner.withBean(SubagentProcessRunner.class, () -> processRunner);
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

    private static List<String> toolBlockKeys(ContextSnapshot context) {
        return context.messages().stream()
            .flatMap(message -> message.content().stream())
            .map(SubagentRuntimeEndToEndTest::toolBlockKey)
            .filter(key -> !key.isEmpty())
            .toList();
    }

    private static String toolBlockKey(ContentBlock block) {
        if (block instanceof ToolCallContentBlock call) {
            return "call:" + call.toolUseId();
        }
        if (block instanceof ToolResultContentBlock result) {
            return "result:" + result.toolUseId();
        }
        return "";
    }

    private static CountDownLatch waitStartedLatch(EventBus eventBus) {
        CountDownLatch waitStarted = new CountDownLatch(1);
        eventBus.subscribe(
            new EventFilter(Optional.of(PARENT_SESSION_ID), Optional.of(ToolProgressEvent.class)),
            envelope -> {
                ToolProgressEvent event = (ToolProgressEvent) envelope.event();
                if ("waiting".equals(event.progress().phase())) {
                    waitStarted.countDown();
                }
            }
        );
        return waitStarted;
    }

    private static String waitResultText(ContextSnapshot context) {
        return context.messages().stream()
            .flatMap(message -> message.content().stream())
            .filter(ToolResultContentBlock.class::isInstance)
            .map(ToolResultContentBlock.class::cast)
            .filter(result -> "toolu_wait_interruptible".equals(result.toolUseId()))
            .map(ToolResultContentBlock::text)
            .findFirst()
            .orElseThrow(() -> new AssertionError("Missing wait_agent result"));
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

    private static final class ControlledSubagentProcessRunner implements SubagentProcessRunner {
        private final CompletableFuture<HeadlessSubagentOutput> completion = new CompletableFuture<>();
        private final AtomicReference<HeadlessSubagentInput> input = new AtomicReference<>();

        @Override
        public SubagentProcessHandle start(HeadlessSubagentInput input) {
            this.input.set(input);
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

        private void completeSuccessfully() {
            HeadlessSubagentInput started = input.get();
            if (started == null) {
                throw new IllegalStateException("subagent process has not started");
            }
            completion.complete(new HeadlessSubagentOutput(
                started.taskName(),
                started.agentId(),
                started.childSessionId(),
                started.runId(),
                SubagentRunStatus.SUCCEEDED,
                "child completed",
                Optional.of("msg_child_final"),
                Optional.empty()
            ));
        }

        private boolean completed() {
            return completion.isDone() && !completion.isCompletedExceptionally() && !completion.isCancelled();
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
        public AssistantEventStream stream(ContextSnapshot context, cn.lycode.contracts.common.AbortSignal signal) {
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
        public AssistantEventStream stream(ContextSnapshot context, cn.lycode.contracts.common.AbortSignal signal) {
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

    private static final class InterleavedWaitParentAiProvider implements AiProviderRuntimePort {
        private final List<ContextSnapshot> contexts = new ArrayList<>();

        @Override
        public AssistantEventStream stream(ContextSnapshot context, cn.lycode.contracts.common.AbortSignal signal) {
            contexts.add(context);
            if (contexts.size() == 1) {
                return new ListAssistantEventStream(List.of(
                    new AssistantStart("msg_parent_spawn_call"),
                    new ToolCallDelta(
                        "toolu_spawn_interleaved",
                        "spawn_agent",
                        Map.of("task_name", "inspect-interleaving", "message", "inspect the session topology"),
                        true
                    ),
                    new AssistantDone(Optional.empty(), Optional.of("tool_calls"))
                ));
            }
            if (contexts.size() == 2) {
                return new ListAssistantEventStream(List.of(
                    new AssistantStart("msg_parent_wait_call"),
                    new ToolCallDelta(
                        "toolu_wait_interleaved",
                        "wait_agent",
                        Map.of("timeout_ms", 1_000),
                        true
                    ),
                    new AssistantDone(Optional.empty(), Optional.of("tool_calls"))
                ));
            }
            return new ListAssistantEventStream(List.of(
                new AssistantStart("msg_parent_after_wait"),
                new AssistantDone(Optional.empty(), Optional.of("end_turn"))
            ));
        }
    }

    private static final class WaitThenFinishParentAiProvider implements AiProviderRuntimePort {
        private final List<ContextSnapshot> contexts = new ArrayList<>();

        @Override
        public AssistantEventStream stream(ContextSnapshot context, cn.lycode.contracts.common.AbortSignal signal) {
            contexts.add(context);
            if (contexts.size() == 1) {
                return new ListAssistantEventStream(List.of(
                    new AssistantStart("msg_parent_wait_interruptible"),
                    new ToolCallDelta(
                        "toolu_wait_interruptible",
                        "wait_agent",
                        Map.of("timeout_ms", 60_000),
                        true
                    ),
                    new AssistantDone(Optional.empty(), Optional.of("tool_calls"))
                ));
            }
            return new ListAssistantEventStream(List.of(
                new AssistantStart("msg_parent_after_interruptible_wait"),
                new AssistantDone(Optional.empty(), Optional.of("end_turn"))
            ));
        }
    }

    private static final class TestSteeringSource implements SteeringMessageSource {
        private final ConcurrentLinkedQueue<SteeringMessage> messages = new ConcurrentLinkedQueue<>();
        private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

        @Override
        public Optional<SteeringMessage> poll() {
            return Optional.ofNullable(messages.poll());
        }

        @Override
        public boolean hasPending() {
            return !messages.isEmpty();
        }

        @Override
        public SignalSubscription subscribe(Runnable listener) {
            listeners.add(listener);
            if (hasPending()) {
                listener.run();
            }
            return () -> listeners.remove(listener);
        }

        private void add(SteeringMessage message) {
            messages.add(message);
            listeners.forEach(Runnable::run);
        }
    }

    private static final class TestAbortSignal implements cn.lycode.contracts.common.AbortSignal {
        private final AtomicBoolean aborted = new AtomicBoolean();
        private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

        @Override
        public boolean aborted() {
            return aborted.get();
        }

        @Override
        public SignalSubscription subscribe(Runnable listener) {
            listeners.add(listener);
            if (aborted()) {
                listener.run();
            }
            return () -> listeners.remove(listener);
        }

        private void abort() {
            if (aborted.compareAndSet(false, true)) {
                listeners.forEach(Runnable::run);
            }
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
