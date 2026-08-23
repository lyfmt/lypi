package cn.lycode.boot.tool;

import cn.lycode.contracts.common.ProgressSink;
import cn.lycode.contracts.common.AbortSignal;
import cn.lycode.contracts.common.ValidationResult;
import cn.lycode.contracts.context.AgentMessage;
import cn.lycode.contracts.context.ContextBudget;
import cn.lycode.contracts.context.ContextSnapshot;
import cn.lycode.contracts.context.MessageKind;
import cn.lycode.contracts.context.MessageRole;
import cn.lycode.contracts.context.ToolResultContentBlock;
import cn.lycode.contracts.event.AgentEvent;
import cn.lycode.contracts.event.EventBus;
import cn.lycode.contracts.event.EventConsumer;
import cn.lycode.contracts.event.EventFilter;
import cn.lycode.contracts.event.EventSubscription;
import cn.lycode.contracts.event.PermissionDecisionEvent;
import cn.lycode.contracts.event.PermissionRequestEvent;
import cn.lycode.contracts.event.PermissionResponseEvent;
import cn.lycode.contracts.event.ToolEndEvent;
import cn.lycode.contracts.event.ToolProgressEvent;
import cn.lycode.contracts.event.ToolStartEvent;
import cn.lycode.contracts.model.ModelSelection;
import cn.lycode.contracts.model.AssistantDone;
import cn.lycode.contracts.model.AssistantEventStream;
import cn.lycode.contracts.model.AssistantStreamEvent;
import cn.lycode.contracts.model.AssistantStreamResult;
import cn.lycode.contracts.model.TextDelta;
import cn.lycode.contracts.model.ThinkingLevel;
import cn.lycode.contracts.mcp.McpServerConfig;
import cn.lycode.contracts.mcp.McpStdioServerConfig;
import cn.lycode.contracts.mcp.McpToolSchema;
import cn.lycode.contracts.mcp.McpTransport;
import cn.lycode.contracts.prompt.SystemPrompt;
import cn.lycode.contracts.resource.ResourceSnapshot;
import cn.lycode.contracts.runtime.SecurityRuntimePort;
import cn.lycode.contracts.runtime.AiProviderRuntimePort;
import cn.lycode.contracts.runtime.AgentCenterPort;
import cn.lycode.contracts.runtime.Executor;
import cn.lycode.contracts.runtime.ResourceRuntimePort;
import cn.lycode.contracts.runtime.SandboxRuntimePolicyKind;
import cn.lycode.contracts.runtime.ToolRuntimePort;
import cn.lycode.contracts.runtime.NetworkMode;
import cn.lycode.contracts.security.AgentMode;
import cn.lycode.contracts.security.ApprovalMode;
import cn.lycode.contracts.security.FileSystemPolicyKind;
import cn.lycode.contracts.security.NetworkPolicyMode;
import cn.lycode.contracts.security.PermissionBehavior;
import cn.lycode.contracts.security.PermissionDecision;
import cn.lycode.contracts.security.PermissionDecisionReason;
import cn.lycode.contracts.security.PermissionMode;
import cn.lycode.contracts.security.PermissionRuntimeState;
import cn.lycode.contracts.common.ToolProgress;
import cn.lycode.contracts.tool.Tool;
import cn.lycode.contracts.tool.ToolRegistrySnapshot;
import cn.lycode.contracts.tool.ToolResult;
import cn.lycode.contracts.tool.ToolUseContext;
import cn.lycode.contracts.tool.ToolUseRequest;
import cn.lycode.contracts.subagent.ExpertAgentDefinition;
import cn.lycode.contracts.subagent.MailboxCommandResult;
import cn.lycode.contracts.subagent.SubagentSpawnRequest;
import cn.lycode.contracts.subagent.SubagentSpawnResult;
import cn.lycode.contracts.subagent.SubagentToolPolicy;
import cn.lycode.contracts.subagent.SubagentWaitRequest;
import cn.lycode.contracts.subagent.SubagentWaitResult;
import cn.lycode.tool.PermissionGateResult;
import cn.lycode.tool.PermissionPromptPort;
import cn.lycode.tool.builtin.ShellEnvironmentHarness;
import cn.lycode.tool.mcp.McpClient;
import cn.lycode.tool.mcp.McpClientManager;
import cn.lycode.tool.mcp.McpClientManagerFactory;
import cn.lycode.runtime.event.InMemoryEventBus;
import cn.lycode.tool.shell.BubblewrapExecutor;
import cn.lycode.tool.shell.ExecutorRegistry;
import cn.lycode.tool.shell.HostExecutor;
import cn.lycode.tool.shell.PermissionProfileSandboxPolicyResolver;
import cn.lycode.tool.shell.SandboxPolicyResolver;
import cn.lycode.security.PermissionProfileConfigCompiler;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class LyCodeToolAutoConfigurationTest {
    @Test
    void configuresDefaultShellStateRoot() {
        new ApplicationContextRunner()
            .withUserConfiguration(LyCodeToolAutoConfiguration.class)
            .withBean(SecurityRuntimePort.class, () -> LyCodeToolAutoConfigurationTest::allowAllSecurity)
            .run(context -> {
                assertThat(context).hasSingleBean(ShellEnvironmentHarness.class);
                assertThat(context.getBean(ShellEnvironmentHarness.class).stateRoot())
                    .isEqualTo(ShellEnvironmentHarness.defaultStateRoot().toAbsolutePath().normalize());
            });
    }

    @Test
    void configuresCustomShellStateRootAndRegistersBash() {
        Path stateRoot = Path.of("build/custom-shell-state").toAbsolutePath().normalize();

        new ApplicationContextRunner()
            .withUserConfiguration(LyCodeToolAutoConfiguration.class)
            .withPropertyValues("lycode.tool.shell.state-root=" + stateRoot)
            .withBean(SecurityRuntimePort.class, () -> LyCodeToolAutoConfigurationTest::allowAllSecurity)
            .run(context -> {
                ShellEnvironmentHarness harness = context.getBean(ShellEnvironmentHarness.class);

                assertThat(harness.stateRoot()).isEqualTo(stateRoot);
                assertThat(context.getBean(ToolRuntimePort.class).resolve("bash")).isPresent();
            });
    }

    @Test
    void sharesUserProvidedShellHarnessAcrossRuntimeFactoryPaths() {
        ShellEnvironmentHarness shellHarness = new ShellEnvironmentHarness(Path.of("build/shared-shell-state"));

        new ApplicationContextRunner()
            .withUserConfiguration(LyCodeToolAutoConfiguration.class)
            .withBean(SecurityRuntimePort.class, () -> LyCodeToolAutoConfigurationTest::allowAllSecurity)
            .withBean(ShellEnvironmentHarness.class, () -> shellHarness)
            .run(context -> {
                ToolRuntimeFactoryPort factory = context.getBean(ToolRuntimeFactoryPort.class);
                ToolRuntimePort main = context.getBean(ToolRuntimePort.class);
                ToolRuntimePort child = factory.create(Path.of("."));
                ToolRuntimePort filtered = factory.create(
                    Path.of("."),
                    new SubagentToolPolicy(List.of("bash"), List.of("bash"))
                );

                assertThat(context).hasSingleBean(ShellEnvironmentHarness.class);
                assertThat(context.getBean(ShellEnvironmentHarness.class)).isSameAs(shellHarness);
                assertThat(shellHarnessFrom(main)).isSameAs(shellHarness);
                assertThat(shellHarnessFrom(child)).isSameAs(shellHarness);
                assertThat(shellHarnessFrom(filtered)).isSameAs(shellHarness);
            });
    }

    @Test
    void createsSandboxExecutorChainAndRegistersDefaultTools() {
        new ApplicationContextRunner()
            .withUserConfiguration(LyCodeToolAutoConfiguration.class)
            .withBean(SecurityRuntimePort.class, () -> LyCodeToolAutoConfigurationTest::allowAllSecurity)
            .run(context -> {
                assertThat(context).hasSingleBean(HostExecutor.class);
                assertThat(context).hasSingleBean(BubblewrapExecutor.class);
                assertThat(context).hasSingleBean(SandboxPolicyResolver.class);
                assertThat(context.getBean(SandboxPolicyResolver.class))
                    .isInstanceOf(PermissionProfileSandboxPolicyResolver.class);
                assertThat(context).hasSingleBean(ExecutorRegistry.class);
                assertThat(context.getBean(Executor.class).name()).isEqualTo("executor-registry");

                ToolRuntimePort runtime = context.getBean(ToolRuntimePort.class);
                assertThat(runtime.resolve("bash")).isPresent();
                assertThat(runtime.resolve("read")).isPresent();
                assertThat(runtime.resolve("glob")).isPresent();
            });
    }

    @Test
    void defaultSandboxResolverFollowsRuntimePermissionMode() {
        new ApplicationContextRunner()
            .withUserConfiguration(LyCodeToolAutoConfiguration.class)
            .withBean(SecurityRuntimePort.class, () -> LyCodeToolAutoConfigurationTest::allowAllSecurity)
            .run(context -> {
                SandboxPolicyResolver resolver = context.getBean(SandboxPolicyResolver.class);
                Path cwd = Path.of(".").toAbsolutePath();

                assertThat(resolver.resolve(cwd, cwd, PermissionRuntimeState.forMode(PermissionMode.ASK)).kind())
                    .isEqualTo(SandboxRuntimePolicyKind.MANAGED);
                assertThat(resolver.resolve(cwd, cwd, PermissionRuntimeState.forMode(PermissionMode.BYPASS)).kind())
                    .isEqualTo(SandboxRuntimePolicyKind.DISABLED);
            });
    }

    @Test
    void explicitDefaultPermissionsProfileKeepsBypassDisabledAndAppliesToAskAndAuto() {
        new ApplicationContextRunner()
            .withUserConfiguration(LyCodeToolAutoConfiguration.class)
            .withPropertyValues("lycode.permissions.default-permissions=:workspace")
            .withBean(SecurityRuntimePort.class, () -> LyCodeToolAutoConfigurationTest::allowAllSecurity)
            .run(context -> {
                SandboxPolicyResolver resolver = context.getBean(SandboxPolicyResolver.class);
                Path cwd = Path.of(".").toAbsolutePath();

                assertThat(resolver.resolve(cwd, cwd, PermissionRuntimeState.forMode(PermissionMode.ASK)).kind())
                    .isEqualTo(SandboxRuntimePolicyKind.MANAGED);
                assertThat(resolver.resolve(cwd, cwd, PermissionRuntimeState.forMode(PermissionMode.AUTO)).kind())
                    .isEqualTo(SandboxRuntimePolicyKind.MANAGED);
                assertThat(resolver.resolve(cwd, cwd, PermissionRuntimeState.forMode(PermissionMode.BYPASS)).kind())
                    .isEqualTo(SandboxRuntimePolicyKind.DISABLED);
                assertThat(resolver.resolve(cwd, cwd, PermissionRuntimeState.forMode(PermissionMode.BYPASS)).networkMode())
                    .isEqualTo(NetworkMode.HOST);
            });
    }

    @Test
    void defaultToolRuntimeUsesConfiguredRuntimeCwd() {
        Path runtimeCwd = Path.of("build/test-runtime-cwd").toAbsolutePath().normalize();

        new ApplicationContextRunner()
            .withUserConfiguration(LyCodeToolAutoConfiguration.class)
            .withPropertyValues("lycode.runtime.cwd=" + runtimeCwd)
            .withBean(SecurityRuntimePort.class, () -> LyCodeToolAutoConfigurationTest::allowAllSecurity)
            .run(context -> {
                ToolRuntimePort runtime = context.getBean(ToolRuntimePort.class);

                assertThat(runtime.cwd()).isEqualTo(runtimeCwd);
            });
    }

    @Test
    void bindsSandboxPropertiesIntoDefaultPolicyResolver() {
        new ApplicationContextRunner()
            .withUserConfiguration(LyCodeToolAutoConfiguration.class)
            .withPropertyValues(
                "lycode.tool.sandbox.network-mode=host",
                "lycode.tool.sandbox.fail-if-unavailable=true",
                "lycode.tool.sandbox.auto-allow-bash-if-sandboxed=true"
            )
            .withBean(SecurityRuntimePort.class, () -> LyCodeToolAutoConfigurationTest::allowAllSecurity)
            .run(context -> {
                SandboxPolicyResolver resolver = context.getBean(SandboxPolicyResolver.class);

                cn.lycode.contracts.runtime.SandboxRuntimePolicy policy = resolver.resolve(Path.of(".").toAbsolutePath(), Path.of(".").toAbsolutePath());

                assertThat(policy.kind()).isEqualTo(SandboxRuntimePolicyKind.MANAGED);
                assertThat(policy.networkMode()).isEqualTo(NetworkMode.HOST);
                assertThat(policy.failIfUnavailable()).isTrue();
                assertThat(policy.autoAllowBashIfSandboxed()).isTrue();
            });
    }

    @Test
    void disablesBashAutoAllowWhenSandboxIsDisabled() {
        new ApplicationContextRunner()
            .withUserConfiguration(LyCodeToolAutoConfiguration.class)
            .withPropertyValues(
                "lycode.tool.sandbox.enabled=false",
                "lycode.tool.sandbox.fail-if-unavailable=true",
                "lycode.tool.sandbox.auto-allow-bash-if-sandboxed=true"
            )
            .withBean(SecurityRuntimePort.class, () -> LyCodeToolAutoConfigurationTest::allowAllSecurity)
            .run(context -> {
                SandboxPolicyResolver resolver = context.getBean(SandboxPolicyResolver.class);

                cn.lycode.contracts.runtime.SandboxRuntimePolicy policy = resolver.resolve(Path.of(".").toAbsolutePath(), Path.of(".").toAbsolutePath());

                assertThat(policy.autoAllowBashIfSandboxed()).isFalse();
            });
    }

    @Test
    void bindsCodexStylePermissionsConfigAndUsesCompiledProfileResolver() {
        Path extraRoot = Path.of("/tmp/lycode-extra-root").toAbsolutePath().normalize();

        new ApplicationContextRunner()
            .withUserConfiguration(LyCodeToolAutoConfiguration.class)
            .withPropertyValues(
                "lycode.permissions.default-permissions=dev",
                "lycode.permissions.approval-policy.mode=granular",
                "lycode.permissions.approval-policy.granular.sandbox-approval=on_request",
                "lycode.permissions.approval-policy.granular.rules=never",
                "lycode.permissions.approval-policy.granular.skill-approval=on_request",
                "lycode.permissions.approval-policy.granular.request-permissions=on_request",
                "lycode.permissions.approval-policy.granular.mcp-elicitations=never",
                "lycode.permissions.profiles.dev.description=Developer profile",
                "lycode.permissions.profiles.dev.extends-profile=:workspace",
                "lycode.permissions.profiles.dev.workspace-roots[0]=" + extraRoot,
                "lycode.permissions.profiles.dev.file-system.kind=restricted",
                "lycode.permissions.profiles.dev.file-system.entries[0].path.kind=special",
                "lycode.permissions.profiles.dev.file-system.entries[0].path.value=:root",
                "lycode.permissions.profiles.dev.file-system.entries[0].access=read",
                "lycode.permissions.profiles.dev.file-system.entries[1].path.kind=exact_path",
                "lycode.permissions.profiles.dev.file-system.entries[1].path.value=/tmp/lycode-cache",
                "lycode.permissions.profiles.dev.file-system.entries[1].access=write",
                "lycode.permissions.profiles.dev.network.mode=enabled"
            )
            .withBean(SecurityRuntimePort.class, () -> LyCodeToolAutoConfigurationTest::allowAllSecurity)
            .run(context -> {
                assertThat(context).hasSingleBean(LyCodePermissionsProperties.class);
                assertThat(context).hasSingleBean(PermissionProfileConfigCompiler.class);

                LyCodePermissionsProperties properties = context.getBean(LyCodePermissionsProperties.class);
                assertThat(properties.getDefaultPermissions()).isEqualTo("dev");
                assertThat(properties.getApprovalPolicy().toApprovalPolicy().mode()).isEqualTo(ApprovalMode.GRANULAR);
                assertThat(properties.getApprovalPolicy().toApprovalPolicy().granularApprovalPolicy().orElseThrow().rules())
                    .isEqualTo(ApprovalMode.NEVER);
                assertThat(properties.getProfiles().get("dev").toConfig().workspaceRoots()).contains(extraRoot);
                assertThat(properties.getProfiles().get("dev").toConfig().fileSystem().orElseThrow().kind())
                    .isEqualTo(FileSystemPolicyKind.RESTRICTED);
                assertThat(properties.getProfiles().get("dev").toConfig().network().orElseThrow().mode())
                    .isEqualTo(NetworkPolicyMode.ENABLED);

                SandboxPolicyResolver resolver = context.getBean(SandboxPolicyResolver.class);
                cn.lycode.contracts.runtime.SandboxRuntimePolicy policy = resolver.resolve(
                    Path.of(".").toAbsolutePath(),
                    Path.of(".").toAbsolutePath()
                );

                assertThat(policy.kind()).isEqualTo(SandboxRuntimePolicyKind.MANAGED);
                assertThat(policy.networkMode()).isEqualTo(NetworkMode.HOST);
                assertThat(policy.allowRead()).contains(Path.of("/"));
                assertThat(policy.allowWrite()).contains(Path.of("/tmp/lycode-cache"));
                assertThat(policy.allowWrite()).contains(extraRoot);
            });
    }

    @Test
    void explicitPermissionsProfileTakesPrecedenceOverLegacySandboxNetworkMode() {
        new ApplicationContextRunner()
            .withUserConfiguration(LyCodeToolAutoConfiguration.class)
            .withPropertyValues(
                "lycode.tool.sandbox.network-mode=host",
                "lycode.permissions.default-permissions=:read-only"
            )
            .withBean(SecurityRuntimePort.class, () -> LyCodeToolAutoConfigurationTest::allowAllSecurity)
            .run(context -> {
                SandboxPolicyResolver resolver = context.getBean(SandboxPolicyResolver.class);

                cn.lycode.contracts.runtime.SandboxRuntimePolicy policy = resolver.resolve(
                    Path.of(".").toAbsolutePath(),
                    Path.of(".").toAbsolutePath()
                );

                assertThat(policy.networkMode()).isEqualTo(NetworkMode.DISABLED);
            });
    }

    @Test
    void rejectsRelativeOrEscapingPermissionWorkspaceRoots() {
        new ApplicationContextRunner()
            .withUserConfiguration(LyCodeToolAutoConfiguration.class)
            .withPropertyValues(
                "lycode.permissions.default-permissions=dev",
                "lycode.permissions.profiles.dev.extends-profile=:workspace",
                "lycode.permissions.profiles.dev.workspace-roots[0]=../outside"
            )
            .withBean(SecurityRuntimePort.class, () -> LyCodeToolAutoConfigurationTest::allowAllSecurity)
            .run(context -> assertThat(context).hasFailed());

        new ApplicationContextRunner()
            .withUserConfiguration(LyCodeToolAutoConfiguration.class)
            .withPropertyValues(
                "lycode.permissions.default-permissions=dev",
                "lycode.permissions.profiles.dev.extends-profile=:workspace",
                "lycode.permissions.profiles.dev.workspace-roots[0]=/tmp/project/../outside"
            )
            .withBean(SecurityRuntimePort.class, () -> LyCodeToolAutoConfigurationTest::allowAllSecurity)
            .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void keepsUserProvidedExecutor() {
        Executor customExecutor = new Executor() {
            @Override
            public String name() {
                return "custom";
            }

            @Override
            public cn.lycode.contracts.runtime.ExecutionResult execute(
                cn.lycode.contracts.runtime.ExecutionRequest request,
                ProgressSink progress,
                cn.lycode.contracts.common.AbortSignal signal
            ) {
                return new cn.lycode.contracts.runtime.ExecutionResult(0, "", "", false, Optional.empty());
            }
        };

        new ApplicationContextRunner()
            .withUserConfiguration(LyCodeToolAutoConfiguration.class)
            .withBean(SecurityRuntimePort.class, () -> LyCodeToolAutoConfigurationTest::allowAllSecurity)
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
            .withUserConfiguration(LyCodeToolAutoConfiguration.class)
            .withBean(EventBus.class, () -> eventBus)
            .withBean(SecurityRuntimePort.class, () -> LyCodeToolAutoConfigurationTest::allowAllSecurity)
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
            .withUserConfiguration(LyCodeToolAutoConfiguration.class)
            .withBean(SecurityRuntimePort.class, () -> LyCodeToolAutoConfigurationTest::allowAllSecurity)
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
    void autoRuntimeUsesModelPermissionReviewerWithoutUserGate() {
        RecordingPermissionReviewProvider provider = new RecordingPermissionReviewProvider(
            "{\"decision\":\"allow\",\"reason\":\"matches request\"}"
        );

        new ApplicationContextRunner()
            .withUserConfiguration(LyCodeToolAutoConfiguration.class)
            .withBean(SecurityRuntimePort.class, () -> LyCodeToolAutoConfigurationTest::allowAllSecurity)
            .withBean(AiProviderRuntimePort.class, () -> provider)
            .run(context -> {
                ToolRuntimePort runtime = context.getBean(ToolRuntimePort.class);
                runtime.register(new AskTool());

                ToolResult<?> result = runtime.execute(
                    List.of(new ToolUseRequest("toolu_1", "ask-test", Map.of("text", "approved"), "msg_1")),
                    context(PermissionMode.AUTO)
                ).getFirst();

                assertThat(result.isError()).isFalse();
                assertThat(provider.calls).isEqualTo(1);
                assertThat(provider.tools.tools()).isEmpty();
                assertThat(provider.context.model()).isEqualTo(context(PermissionMode.AUTO).model());
            });
    }

    @Test
    void headlessTransportDeniesAskPermissionWithoutWaitingForResponse() throws Exception {
        InMemoryEventBus eventBus = new InMemoryEventBus();
        List<AgentEvent> events = new ArrayList<>();
        eventBus.subscribe(new EventFilter(Optional.empty(), Optional.empty()), envelope -> events.add(envelope.event()));

        new ApplicationContextRunner()
            .withUserConfiguration(LyCodeToolAutoConfiguration.class)
            .withBean(EventBus.class, () -> eventBus)
            .withBean(SecurityRuntimePort.class, () -> LyCodeToolAutoConfigurationTest::allowAllSecurity)
            .withBean(cn.lycode.transport.headless.HeadlessTransport.class, LyCodeToolAutoConfigurationTest::headlessTransport)
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
            .withUserConfiguration(LyCodeToolAutoConfiguration.class)
            .withPropertyValues("lycode.runtime.transport=tui")
            .withBean(EventBus.class, () -> eventBus)
            .withBean(SecurityRuntimePort.class, () -> LyCodeToolAutoConfigurationTest::allowAllSecurity)
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
            .withUserConfiguration(LyCodeToolAutoConfiguration.class)
            .withBean(EventBus.class, () -> eventBus)
            .withBean(SecurityRuntimePort.class, () -> LyCodeToolAutoConfigurationTest::allowAllSecurity)
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
            .withUserConfiguration(LyCodeToolAutoConfiguration.class)
            .withBean(SecurityRuntimePort.class, () -> LyCodeToolAutoConfigurationTest::allowAllSecurity)
            .withBean(AgentCenterPort.class, LyCodeToolAutoConfigurationTest::agentCenter)
            .run(context -> {
                ToolRuntimePort runtime = context.getBean(ToolRuntimePort.class);

                assertThat(runtime.resolve("spawn_agent")).isPresent();
                assertThat(runtime.resolve("wait_agent")).isPresent();
                assertThat(runtime.resolve("continue_agent")).isEmpty();
                assertThat(runtime.resolve("read_agent_result")).isEmpty();
                assertThat(runtime.resolve("read_mailbox")).isEmpty();
                assertThat(runtime.resolve("list_agents")).isEmpty();
            });
    }

    @Test
    void registersMcpToolsFromResourceRuntime() {
        RecordingMcpClientFactory mcpClients = new RecordingMcpClientFactory();

        new ApplicationContextRunner()
            .withUserConfiguration(LyCodeToolAutoConfiguration.class)
            .withBean(SecurityRuntimePort.class, () -> LyCodeToolAutoConfigurationTest::allowAllSecurity)
            .withBean(ResourceRuntimePort.class, () -> resourceRuntimeWith(mcpServerConfig()))
            .withBean(McpClientManagerFactory.class, () -> cwd -> mcpClients.manager(cwd))
            .run(context -> {
                ToolRuntimePort runtime = context.getBean(ToolRuntimePort.class);

                assertThat(runtime.resolve("mcp__fake__echo")).isPresent();
                assertThat(mcpClients.connectedConfigs).extracting(McpServerConfig::name).containsExactly("fake");
            });
    }

    @Test
    void loadsResourcesOnceForExpertAndMcpToolRegistration() {
        RecordingMcpClientFactory mcpClients = new RecordingMcpClientFactory();
        AtomicInteger loadCalls = new AtomicInteger();
        ResourceSnapshot resources = resourceSnapshot(
            List.of(mcpServerConfig()),
            List.of(new ExpertAgentDefinition(
                "code-reviewer",
                "openai",
                "gpt-5.4",
                "Review code precisely.",
                List.of("bash"),
                Path.of("agents", "code-reviewer.yaml")
            ))
        );

        new ApplicationContextRunner()
            .withUserConfiguration(LyCodeToolAutoConfiguration.class)
            .withBean(SecurityRuntimePort.class, () -> LyCodeToolAutoConfigurationTest::allowAllSecurity)
            .withBean(AgentCenterPort.class, LyCodeToolAutoConfigurationTest::agentCenter)
            .withBean(ResourceRuntimePort.class, () -> resourceRuntimeWith(resources, loadCalls))
            .withBean(McpClientManagerFactory.class, () -> cwd -> mcpClients.manager(cwd))
            .run(context -> {
                ToolRuntimePort runtime = context.getBean(ToolRuntimePort.class);

                assertThat(loadCalls).hasValue(1);
                assertThat(expertAgentNames(runtime)).containsExactly("code-reviewer");
                assertThat(runtime.resolve("mcp__fake__echo")).isPresent();
            });
    }

    @Test
    void resourceLoadFailureKeepsDefaultAndGenericSubagentToolsAvailable() {
        AtomicInteger loadCalls = new AtomicInteger();
        ResourceRuntimePort failingResources = new ResourceRuntimePort() {
            @Override
            public ResourceSnapshot load(Path cwd) {
                loadCalls.incrementAndGet();
                throw new IllegalStateException("resource unavailable");
            }

            @Override
            public SystemPrompt buildSystemPrompt(ResourceSnapshot resources) {
                throw new AssertionError("system prompt must not be built during tool registration");
            }
        };

        new ApplicationContextRunner()
            .withUserConfiguration(LyCodeToolAutoConfiguration.class)
            .withBean(SecurityRuntimePort.class, () -> LyCodeToolAutoConfigurationTest::allowAllSecurity)
            .withBean(AgentCenterPort.class, LyCodeToolAutoConfigurationTest::agentCenter)
            .withBean(ResourceRuntimePort.class, () -> failingResources)
            .run(context -> {
                ToolRuntimePort runtime = context.getBean(ToolRuntimePort.class);

                assertThat(loadCalls).hasValue(1);
                assertThat(runtime.resolve("bash")).isPresent();
                assertThat(runtime.resolve("spawn_agent")).isPresent();
                assertThat(expertAgentNames(runtime)).isEmpty();
            });
    }

    @Test
    void closesMcpManagersWhenContextCloses() {
        RecordingMcpClientFactory mcpClients = new RecordingMcpClientFactory();

        new ApplicationContextRunner()
            .withUserConfiguration(LyCodeToolAutoConfiguration.class)
            .withBean(SecurityRuntimePort.class, () -> LyCodeToolAutoConfigurationTest::allowAllSecurity)
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
            .withUserConfiguration(LyCodeToolAutoConfiguration.class)
            .withBean(SecurityRuntimePort.class, () -> LyCodeToolAutoConfigurationTest::allowAllSecurity)
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
            .withUserConfiguration(LyCodeToolAutoConfiguration.class)
            .withBean(SecurityRuntimePort.class, () -> LyCodeToolAutoConfigurationTest::allowAllSecurity)
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
            .withUserConfiguration(LyCodeToolAutoConfiguration.class)
            .withBean(SecurityRuntimePort.class, () -> LyCodeToolAutoConfigurationTest::allowAllSecurity)
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
            public cn.lycode.contracts.tool.ToolRegistrySnapshot snapshot() {
                return new cn.lycode.contracts.tool.ToolRegistrySnapshot(List.of());
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
            .withUserConfiguration(LyCodeToolAutoConfiguration.class)
            .withBean(SecurityRuntimePort.class, () -> LyCodeToolAutoConfigurationTest::allowAllSecurity)
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

    private static Object shellHarnessFrom(ToolRuntimePort runtime) {
        return ReflectionTestUtils.getField(runtime.resolve("bash").orElseThrow(), "shellHarness");
    }

    private static ContextSnapshot context() {
        return context(PermissionMode.ASK);
    }

    private static ContextSnapshot context(PermissionMode permissionMode) {
        return new ContextSnapshot(
            new SystemPrompt("system", List.of(), "hash"),
            List.of(),
            new ModelSelection("provider", "model", ThinkingLevel.MEDIUM),
            ThinkingLevel.MEDIUM,
            AgentMode.EXECUTE,
            permissionMode,
            new ContextBudget(0, 0, 0, 0, 0, 0L, 0L, BigDecimal.ZERO)
        );
    }

    private static ResourceRuntimePort resourceRuntimeWith(McpServerConfig config) {
        return resourceRuntimeWith(resourceSnapshot(List.of(config), List.of()), new AtomicInteger());
    }

    private static ResourceRuntimePort resourceRuntimeWith(ResourceSnapshot snapshot, AtomicInteger loadCalls) {
        return new ResourceRuntimePort() {
            @Override
            public ResourceSnapshot load(Path cwd) {
                loadCalls.incrementAndGet();
                return snapshot;
            }

            @Override
            public SystemPrompt buildSystemPrompt(ResourceSnapshot resources) {
                return new SystemPrompt("system", List.of(), "hash");
            }
        };
    }

    private static ResourceSnapshot resourceSnapshot(
        List<McpServerConfig> mcpServers,
        List<ExpertAgentDefinition> expertAgents
    ) {
        return new ResourceSnapshot(
            List.of(),
            List.of(),
            new cn.lycode.contracts.skill.SkillIndex(List.of(), List.of()),
            List.of(),
            mcpServers,
            expertAgents,
            List.of()
        );
    }

    private static List<String> expertAgentNames(ToolRuntimePort runtime) {
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) runtime.resolve("spawn_agent")
            .orElseThrow()
            .inputSchema()
            .value()
            .get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> agent = (Map<String, Object>) properties.get("agent");
        @SuppressWarnings("unchecked")
        List<String> names = (List<String>) agent.get("enum");
        return names;
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
            new cn.lycode.contracts.common.JsonSchema(Map.of("type", "object")),
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
            public SubagentWaitResult waitFor(SubagentWaitRequest request) {
                return SubagentWaitResult.timedOut();
            }
        };
    }

    private static cn.lycode.transport.headless.HeadlessTransport headlessTransport() {
        return new cn.lycode.transport.headless.HeadlessTransport() {
            @Override
            public String name() {
                return "headless";
            }

            @Override
            public void attach(EventBus events, cn.lycode.contracts.tui.SessionRuntimeState state) {
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

    private static final class RecordingPermissionReviewProvider implements AiProviderRuntimePort {
        private final String output;
        private ContextSnapshot context;
        private ToolRegistrySnapshot tools;
        private int calls;

        private RecordingPermissionReviewProvider(String output) {
            this.output = output;
        }

        @Override
        public AssistantEventStream stream(ContextSnapshot context, AbortSignal signal) {
            throw new AssertionError("reviewer must provide an explicit empty tool snapshot");
        }

        @Override
        public AssistantEventStream stream(
            ContextSnapshot context,
            ToolRegistrySnapshot tools,
            AbortSignal signal
        ) {
            this.context = context;
            this.tools = tools;
            calls++;
            List<AssistantStreamEvent> events = List.of(
                new TextDelta(output),
                new AssistantDone(Optional.empty(), Optional.of("stop"))
            );
            return new AssistantEventStream() {
                @Override
                public Iterator<AssistantStreamEvent> iterator() {
                    return events.iterator();
                }

                @Override
                public AssistantStreamResult result() {
                    return new AssistantStreamResult(
                        "review",
                        events,
                        Optional.empty(),
                        Optional.of("stop"),
                        true,
                        false,
                        Optional.empty()
                    );
                }

                @Override
                public void close() {
                }
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
        public cn.lycode.contracts.common.JsonSchema inputSchema() {
            return new cn.lycode.contracts.common.JsonSchema(Map.of());
        }

        @Override
        public cn.lycode.contracts.tool.InterruptBehavior interruptBehavior() {
            return cn.lycode.contracts.tool.InterruptBehavior.CANCEL;
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
        public cn.lycode.contracts.common.JsonSchema inputSchema() {
            return new cn.lycode.contracts.common.JsonSchema(Map.of());
        }

        @Override
        public cn.lycode.contracts.tool.InterruptBehavior interruptBehavior() {
            return cn.lycode.contracts.tool.InterruptBehavior.CANCEL;
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
