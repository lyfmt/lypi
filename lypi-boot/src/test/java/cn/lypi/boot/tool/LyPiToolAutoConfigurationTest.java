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
import cn.lypi.contracts.event.PermissionResponseEvent;
import cn.lypi.contracts.event.ToolEndEvent;
import cn.lypi.contracts.event.ToolProgressEvent;
import cn.lypi.contracts.event.ToolStartEvent;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.mcp.McpServerConfig;
import cn.lypi.contracts.mcp.McpStdioServerConfig;
import cn.lypi.contracts.mcp.McpToolSchema;
import cn.lypi.contracts.mcp.McpTransport;
import cn.lypi.contracts.prompt.SystemPrompt;
import cn.lypi.contracts.resource.ResourceSnapshot;
import cn.lypi.contracts.runtime.SecurityRuntimePort;
import cn.lypi.contracts.runtime.AgentCenterPort;
import cn.lypi.contracts.runtime.AgentRegistryPort;
import cn.lypi.contracts.runtime.Executor;
import cn.lypi.contracts.runtime.MailboxPort;
import cn.lypi.contracts.runtime.ResourceRuntimePort;
import cn.lypi.contracts.runtime.ToolRuntimePort;
import cn.lypi.contracts.runtime.NetworkMode;
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
import cn.lypi.contracts.subagent.SubagentToolPolicy;
import cn.lypi.contracts.subagent.HeadlessSubagentOutput;
import cn.lypi.contracts.subagent.AgentRunStatus;
import cn.lypi.contracts.subagent.AgentView;
import cn.lypi.contracts.subagent.MailboxCommandResult;
import cn.lypi.contracts.subagent.MailboxMessage;
import cn.lypi.contracts.subagent.MailboxStatus;
import cn.lypi.contracts.subagent.SubagentSpawnRequest;
import cn.lypi.contracts.subagent.SubagentSpawnResult;
import cn.lypi.tool.PermissionGateResult;
import cn.lypi.tool.PermissionPromptPort;
import cn.lypi.tool.mcp.McpClient;
import cn.lypi.tool.mcp.McpClientManager;
import cn.lypi.tool.mcp.McpClientManagerFactory;
import cn.lypi.runtime.event.InMemoryEventBus;
import cn.lypi.tool.shell.BubblewrapExecutor;
import cn.lypi.tool.shell.ExecutorRegistry;
import cn.lypi.tool.shell.HostExecutor;
import cn.lypi.tool.shell.SandboxPolicyResolver;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class LyPiToolAutoConfigurationTest {
    @Test
    void createsSandboxExecutorChainAndRegistersDefaultTools() {
        new ApplicationContextRunner()
            .withUserConfiguration(LyPiToolAutoConfiguration.class)
            .withBean(SecurityRuntimePort.class, () -> LyPiToolAutoConfigurationTest::allowAllSecurity)
            .run(context -> {
                assertThat(context).hasSingleBean(HostExecutor.class);
                assertThat(context).hasSingleBean(BubblewrapExecutor.class);
                assertThat(context).hasSingleBean(SandboxPolicyResolver.class);
                assertThat(context).hasSingleBean(ExecutorRegistry.class);
                assertThat(context.getBean(Executor.class).name()).isEqualTo("executor-registry");

                ToolRuntimePort runtime = context.getBean(ToolRuntimePort.class);
                assertThat(runtime.resolve("bash")).isPresent();
                assertThat(runtime.resolve("read")).isPresent();
                assertThat(runtime.resolve("glob")).isPresent();
            });
    }

    @Test
    void defaultToolRuntimeUsesConfiguredRuntimeCwd() {
        Path runtimeCwd = Path.of("build/test-runtime-cwd").toAbsolutePath().normalize();

        new ApplicationContextRunner()
            .withUserConfiguration(LyPiToolAutoConfiguration.class)
            .withPropertyValues("lypi.runtime.cwd=" + runtimeCwd)
            .withBean(SecurityRuntimePort.class, () -> LyPiToolAutoConfigurationTest::allowAllSecurity)
            .run(context -> {
                ToolRuntimePort runtime = context.getBean(ToolRuntimePort.class);

                assertThat(runtime.cwd()).isEqualTo(runtimeCwd);
            });
    }

    @Test
    void bindsSandboxPropertiesIntoDefaultPolicyResolver() {
        new ApplicationContextRunner()
            .withUserConfiguration(LyPiToolAutoConfiguration.class)
            .withPropertyValues(
                "lypi.tool.sandbox.network-mode=host",
                "lypi.tool.sandbox.fail-if-unavailable=true",
                "lypi.tool.sandbox.auto-allow-bash-if-sandboxed=true"
            )
            .withBean(SecurityRuntimePort.class, () -> LyPiToolAutoConfigurationTest::allowAllSecurity)
            .run(context -> {
                SandboxPolicyResolver resolver = context.getBean(SandboxPolicyResolver.class);

                cn.lypi.contracts.runtime.SandboxRuntimePolicy policy = resolver.resolve(Path.of(".").toAbsolutePath(), Path.of(".").toAbsolutePath());

                assertThat(policy.networkMode()).isEqualTo(NetworkMode.HOST);
                assertThat(policy.failIfUnavailable()).isTrue();
                assertThat(policy.autoAllowBashIfSandboxed()).isTrue();
            });
    }

    @Test
    void disablesBashAutoAllowWhenSandboxIsDisabled() {
        new ApplicationContextRunner()
            .withUserConfiguration(LyPiToolAutoConfiguration.class)
            .withPropertyValues(
                "lypi.tool.sandbox.enabled=false",
                "lypi.tool.sandbox.fail-if-unavailable=true",
                "lypi.tool.sandbox.auto-allow-bash-if-sandboxed=true"
            )
            .withBean(SecurityRuntimePort.class, () -> LyPiToolAutoConfigurationTest::allowAllSecurity)
            .run(context -> {
                SandboxPolicyResolver resolver = context.getBean(SandboxPolicyResolver.class);

                cn.lypi.contracts.runtime.SandboxRuntimePolicy policy = resolver.resolve(Path.of(".").toAbsolutePath(), Path.of(".").toAbsolutePath());

                assertThat(policy.autoAllowBashIfSandboxed()).isFalse();
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
    void headlessTransportDeniesAskPermissionWithoutWaitingForResponse() throws Exception {
        InMemoryEventBus eventBus = new InMemoryEventBus();
        List<AgentEvent> events = new ArrayList<>();
        eventBus.subscribe(new EventFilter(Optional.empty(), Optional.empty()), envelope -> events.add(envelope.event()));

        new ApplicationContextRunner()
            .withUserConfiguration(LyPiToolAutoConfiguration.class)
            .withBean(EventBus.class, () -> eventBus)
            .withBean(SecurityRuntimePort.class, () -> LyPiToolAutoConfigurationTest::allowAllSecurity)
            .withBean(cn.lypi.transport.headless.HeadlessTransport.class, LyPiToolAutoConfigurationTest::headlessTransport)
            .run(context -> {
                ToolRuntimePort runtime = context.getBean(ToolRuntimePort.class);
                runtime.register(new AskTool());

                CompletableFuture<ToolResult<?>> resultFuture = CompletableFuture.supplyAsync(() -> runtime.execute(
                    List.of(new ToolUseRequest("toolu_1", "ask-test", Map.of("text", "ignored"), "msg_1")),
                    context()
                ).getFirst());

                ToolResult<?> result = resultFuture.get(500, TimeUnit.MILLISECONDS);

                assertThat(result.isError()).isTrue();
                assertThat(((ToolResultContentBlock) result.newMessages().getFirst().content().getFirst()).text())
                    .contains("权限请求未获允许");
                assertThat(events.stream().map(AgentEvent::getClass))
                    .containsSequence(PermissionRequestEvent.class, PermissionDecisionEvent.class);
            });
    }

    @Test
    void tuiPermissionResponseUnlocksWaitingToolExecution() {
        InMemoryEventBus eventBus = new InMemoryEventBus();
        CountDownLatch requestPublished = new CountDownLatch(1);
        List<AgentEvent> events = new ArrayList<>();
        AtomicReference<PermissionRequestEvent> requestRef = new AtomicReference<>();
        eventBus.subscribe(new EventFilter(Optional.empty(), Optional.empty()), envelope -> {
            events.add(envelope.event());
            if (envelope.event() instanceof PermissionRequestEvent request) {
                requestRef.set(request);
                requestPublished.countDown();
            }
        });

        new ApplicationContextRunner()
            .withUserConfiguration(LyPiToolAutoConfiguration.class)
            .withPropertyValues("lypi.runtime.transport=tui")
            .withBean(EventBus.class, () -> eventBus)
            .withBean(SecurityRuntimePort.class, () -> LyPiToolAutoConfigurationTest::allowAllSecurity)
            .run(context -> {
                ToolRuntimePort runtime = context.getBean(ToolRuntimePort.class);
                runtime.register(new AskTool());

                CompletableFuture<ToolResult<?>> resultFuture = CompletableFuture.supplyAsync(() -> runtime.execute(
                    List.of(new ToolUseRequest("toolu_1", "ask-test", Map.of("text", "approved"), "msg_1")),
                    context()
                ).getFirst());

                assertThat(requestPublished.await(2, TimeUnit.SECONDS)).isTrue();
                PermissionRequestEvent request = requestRef.get();
                eventBus.publish(new PermissionResponseEvent(
                    request.sessionId(),
                    request.requestId(),
                    request.defaultOptionId(),
                    false,
                    Instant.now()
                ));

                ToolResult<?> result = resultFuture.get(2, TimeUnit.SECONDS);

                assertThat(result.isError()).isFalse();
                assertThat(events).anySatisfy(event -> {
                    assertThat(event).isInstanceOf(PermissionDecisionEvent.class);
                    PermissionDecisionEvent decision = (PermissionDecisionEvent) event;
                    assertThat(decision.requestId()).isEqualTo(request.requestId());
                    assertThat(decision.selectedOptionId()).isEqualTo(request.defaultOptionId());
                });
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
            .withBean(AgentRegistryPort.class, LyPiToolAutoConfigurationTest::agentRegistry)
            .run(context -> {
                ToolRuntimePort runtime = context.getBean(ToolRuntimePort.class);

                assertThat(runtime.resolve("spawn_agent")).isPresent();
                assertThat(runtime.resolve("read_agent_result")).isPresent();
                assertThat(runtime.resolve("read_mailbox")).isPresent();
                assertThat(runtime.resolve("list_agents")).isPresent();
            });
    }

    @Test
    void registersMcpToolsFromResourceRuntime() {
        RecordingMcpClientFactory mcpClients = new RecordingMcpClientFactory();

        new ApplicationContextRunner()
            .withUserConfiguration(LyPiToolAutoConfiguration.class)
            .withBean(SecurityRuntimePort.class, () -> LyPiToolAutoConfigurationTest::allowAllSecurity)
            .withBean(ResourceRuntimePort.class, () -> resourceRuntimeWith(mcpServerConfig()))
            .withBean(McpClientManagerFactory.class, () -> cwd -> mcpClients.manager(cwd))
            .run(context -> {
                ToolRuntimePort runtime = context.getBean(ToolRuntimePort.class);

                assertThat(runtime.resolve("mcp__fake__echo")).isPresent();
                assertThat(mcpClients.connectedConfigs).extracting(McpServerConfig::name).containsExactly("fake");
            });
    }

    @Test
    void closesMcpManagersWhenContextCloses() {
        RecordingMcpClientFactory mcpClients = new RecordingMcpClientFactory();

        new ApplicationContextRunner()
            .withUserConfiguration(LyPiToolAutoConfiguration.class)
            .withBean(SecurityRuntimePort.class, () -> LyPiToolAutoConfigurationTest::allowAllSecurity)
            .withBean(ResourceRuntimePort.class, () -> resourceRuntimeWith(mcpServerConfig()))
            .withBean(McpClientManagerFactory.class, () -> cwd -> mcpClients.manager(cwd))
            .run(context -> {
                assertThat(context.getBean(ToolRuntimePort.class).resolve("mcp__fake__echo")).isPresent();
                assertThat(mcpClients.closed).isFalse();
            });

        assertThat(mcpClients.closed).isTrue();
    }


    @Test
    void mcpConnectionFailureDoesNotBlockBuiltInTools() {
        new ApplicationContextRunner()
            .withUserConfiguration(LyPiToolAutoConfiguration.class)
            .withBean(SecurityRuntimePort.class, () -> LyPiToolAutoConfigurationTest::allowAllSecurity)
            .withBean(ResourceRuntimePort.class, () -> resourceRuntimeWith(mcpServerConfig()))
            .withBean(McpClientManagerFactory.class, () -> cwd -> {
                throw new IllegalStateException("offline");
            })
            .run(context -> {
                ToolRuntimePort runtime = context.getBean(ToolRuntimePort.class);

                assertThat(runtime.resolve("bash")).isPresent();
                assertThat(runtime.resolve("mcp__fake__echo")).isEmpty();
            });
    }

    @Test
    void headlessRuntimeDeniesMcpToolBeforeInvokingManager() {
        RecordingMcpClientFactory mcpClients = new RecordingMcpClientFactory();

        new ApplicationContextRunner()
            .withUserConfiguration(LyPiToolAutoConfiguration.class)
            .withBean(SecurityRuntimePort.class, () -> LyPiToolAutoConfigurationTest::allowAllSecurity)
            .withBean(ResourceRuntimePort.class, () -> resourceRuntimeWith(mcpServerConfig()))
            .withBean(McpClientManagerFactory.class, () -> cwd -> mcpClients.manager(cwd))
            .run(context -> {
                ToolRuntimePort runtime = context.getBean(ToolRuntimePort.class);

                ToolResult<?> result = runtime.execute(
                    List.of(new ToolUseRequest("toolu_mcp", "mcp__fake__echo", Map.of("text", "hi"), "msg_1")),
                    context()
                ).getFirst();

                assertThat(result.isError()).isTrue();
                assertThat(((ToolResultContentBlock) result.newMessages().getFirst().content().getFirst()).text())
                    .contains("权限请求未获允许");
                assertThat(mcpClients.invocations).isEmpty();
            });
    }

    @Test
    void subagentToolPolicyFiltersMcpToolsByCanonicalName() {
        RecordingMcpClientFactory mcpClients = new RecordingMcpClientFactory();

        new ApplicationContextRunner()
            .withUserConfiguration(LyPiToolAutoConfiguration.class)
            .withBean(SecurityRuntimePort.class, () -> LyPiToolAutoConfigurationTest::allowAllSecurity)
            .withBean(ResourceRuntimePort.class, () -> resourceRuntimeWith(mcpServerConfig()))
            .withBean(McpClientManagerFactory.class, () -> cwd -> mcpClients.manager(cwd))
            .run(context -> {
                ToolRuntimeFactoryPort factory = context.getBean(ToolRuntimeFactoryPort.class);
                ToolRuntimePort denied = factory.create(Path.of("."), new SubagentToolPolicy(List.of(), List.of("read")));
                ToolRuntimePort allowed = factory.create(Path.of("."), new SubagentToolPolicy(List.of("mcp__fake__echo"), List.of("mcp__fake__echo")));

                assertThat(denied.resolve("mcp__fake__echo")).isEmpty();
                assertThat(denied.snapshot().tools()).extracting("name").doesNotContain("mcp__fake__echo");
                assertThat(allowed.resolve("mcp__fake__echo")).isPresent();
                assertThat(allowed.snapshot().tools()).extracting("name").contains("mcp__fake__echo");
            });
    }

    @Test
    void defaultToolRuntimeFactoryBacksOffWhenCustomToolRuntimeExists() {
        ToolRuntimePort customRuntime = new ToolRuntimePort() {
            @Override
            public void register(Tool<?, ?> tool) {
            }

            @Override
            public Optional<Tool<?, ?>> resolve(String nameOrAlias) {
                return Optional.empty();
            }

            @Override
            public cn.lypi.contracts.tool.ToolRegistrySnapshot snapshot() {
                return new cn.lypi.contracts.tool.ToolRegistrySnapshot(List.of());
            }

            @Override
            public Path cwd() {
                return Path.of("/custom").toAbsolutePath().normalize();
            }

            @Override
            public List<ToolResult<?>> execute(List<ToolUseRequest> requests, ContextSnapshot context) {
                return List.of();
            }
        };

        new ApplicationContextRunner()
            .withUserConfiguration(LyPiToolAutoConfiguration.class)
            .withBean(SecurityRuntimePort.class, () -> LyPiToolAutoConfigurationTest::allowAllSecurity)
            .withBean(ToolRuntimePort.class, () -> customRuntime)
            .run(context -> {
                assertThat(context).hasSingleBean(ToolRuntimePort.class);
                assertThat(context.getBean(ToolRuntimePort.class)).isSameAs(customRuntime);
                assertThat(context).doesNotHaveBean(ToolRuntimeFactoryPort.class);
            });
    }

    @Test
    void memoryConsolidationToolRuntimeDefaultFailsFastWhenFactoryDoesNotSupportIt() {
        ToolRuntimeFactoryPort factory = cwd -> {
            throw new AssertionError("ordinary factory must not be used");
        };

        org.junit.jupiter.api.Assertions.assertThrows(
            UnsupportedOperationException.class,
            () -> factory.createMemoryConsolidation(Path.of("."), new RecordingEventBus())
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

    private static ResourceRuntimePort resourceRuntimeWith(McpServerConfig config) {
        return new ResourceRuntimePort() {
            @Override
            public ResourceSnapshot load(Path cwd) {
                return new ResourceSnapshot(
                    List.of(),
                    List.of(),
                    new cn.lypi.contracts.skill.SkillIndex(List.of(), List.of()),
                    List.of(),
                    List.of(config),
                    List.of()
                );
            }

            @Override
            public SystemPrompt buildSystemPrompt(ResourceSnapshot resources) {
                return new SystemPrompt("system", List.of(), "hash");
            }
        };
    }

    private static McpServerConfig mcpServerConfig() {
        return new McpServerConfig(
            "fake",
            McpTransport.STDIO,
            new McpStdioServerConfig(List.of("fake"), Map.of()),
            null,
            Duration.ofSeconds(1),
            Duration.ofSeconds(1)
        );
    }

    private static McpToolSchema mcpToolSchema() {
        return new McpToolSchema(
            "fake",
            "echo",
            "mcp__fake__echo",
            new cn.lypi.contracts.common.JsonSchema(Map.of("type", "object")),
            "Echo"
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

    private static AgentRegistryPort agentRegistry() {
        return new AgentRegistryPort() {
            @Override
            public List<AgentView> list(String parentSessionId, java.util.Set<AgentRunStatus> statuses) {
                throw new UnsupportedOperationException("not used");
            }
        };
    }

    private static cn.lypi.transport.headless.HeadlessTransport headlessTransport() {
        return new cn.lypi.transport.headless.HeadlessTransport() {
            @Override
            public String name() {
                return "headless";
            }

            @Override
            public void attach(EventBus events, cn.lypi.contracts.tui.SessionRuntimeState state) {
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

    private static final class RecordingMcpClientFactory {
        private final List<McpServerConfig> connectedConfigs = new ArrayList<>();
        private final List<Map<String, Object>> invocations = new ArrayList<>();
        private boolean closed;

        private McpClientManager manager(Path cwd) {
            return new McpClientManager(cwd, (config, runtimeCwd) -> new McpClient() {
                @Override
                public List<McpToolSchema> connect() {
                    connectedConfigs.add(config);
                    return List.of(mcpToolSchema());
                }

                @Override
                public com.fasterxml.jackson.databind.JsonNode callTool(String toolName, Map<String, Object> arguments) {
                    invocations.add(Map.of("serverName", config.name(), "toolName", toolName, "arguments", arguments));
                    return new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(Map.of(
                        "content", List.of(Map.of("type", "text", "text", arguments.get("text"))),
                        "isError", false
                    ));
                }

                @Override
                public void close() {
                    closed = true;
                }
            });
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
            String output = String.valueOf(input.getOrDefault("text", "approved"));
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
