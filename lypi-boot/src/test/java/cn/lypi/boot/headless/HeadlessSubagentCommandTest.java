package cn.lypi.boot.headless;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lypi.boot.LyPiApplication;
import cn.lypi.contracts.agent.TurnRequest;
import cn.lypi.contracts.agent.TurnState;
import cn.lypi.contracts.agent.TurnStatus;
import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.TextContentBlock;
import cn.lypi.contracts.runtime.AgentCoreFactoryPort;
import cn.lypi.contracts.runtime.AgentCorePort;
import cn.lypi.contracts.runtime.SessionManagerFactoryPort;
import cn.lypi.contracts.runtime.SessionManagerPort;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.subagent.HeadlessSubagentInput;
import cn.lypi.contracts.subagent.HeadlessSubagentOutput;
import cn.lypi.contracts.subagent.SubagentRunStatus;
import cn.lypi.session.SessionManagerImpl;
import cn.lypi.transport.headless.HeadlessSubagentJsonCodec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

class HeadlessSubagentCommandTest {
    @TempDir
    Path tempDir;

    @Test
    void applicationDeclaresMainEntryPoint() throws Exception {
        Method main = LyPiApplication.class.getDeclaredMethod("main", String[].class);

        assertThat(main).isNotNull();
    }

    @Test
    void applicationEntryPointCanBeCreatedWithProgrammaticDefaults() {
        SpringApplication application = LyPiApplication.application();

        assertThat(application).isNotNull();
    }

    @Test
    void defaultConfigurationDisablesSpringBootBanner() throws Exception {
        try (InputStream input = HeadlessSubagentCommandTest.class.getResourceAsStream("/application.yml")) {
            assertThat(input).isNotNull();
            String yaml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(yaml).contains("banner-mode: off");
            assertThat(yaml).doesNotContain("root: off");
        }
    }

    @Test
    void headlessApplicationDefaultsDisableLoggingSystemForJsonProtocolMode() {
        SpringApplication headless = LyPiApplication.application(new String[] {"--lypi.headless.subagent=true"});
        SpringApplication regular = LyPiApplication.application();

        @SuppressWarnings("unchecked")
        Map<String, Object> headlessDefaults = (Map<String, Object>) ReflectionTestUtils.getField(
            headless,
            "defaultProperties"
        );
        Object regularDefaults = ReflectionTestUtils.getField(regular, "defaultProperties");

        assertThat(headlessDefaults).containsEntry("logging.level.root", "off");
        assertThat(headlessDefaults).containsEntry(LoggingSystem.SYSTEM_PROPERTY, LoggingSystem.NONE);
        assertThat(regularDefaults).isNull();
    }

    @Test
    void headlessApplicationForcesJvmLoggingSystemOffBeforeBootStarts() {
        String previous = System.getProperty(LoggingSystem.SYSTEM_PROPERTY);
        System.setProperty(LoggingSystem.SYSTEM_PROPERTY, "example.LoggingSystem");
        try {
            LyPiApplication.application(new String[] {"headless-subagent"});

            assertThat(System.getProperty(LoggingSystem.SYSTEM_PROPERTY)).isEqualTo(LoggingSystem.NONE);
        } finally {
            if (previous == null) {
                System.clearProperty(LoggingSystem.SYSTEM_PROPERTY);
            } else {
                System.setProperty(LoggingSystem.SYSTEM_PROPERTY, previous);
            }
        }
    }

    @Test
    void autoConfigurationCreatesHeadlessSubagentCommandWhenDependenciesExist() {
        new ApplicationContextRunner()
            .withUserConfiguration(HeadlessSubagentCommandAutoConfiguration.class)
            .withBean(AgentCoreFactoryPort.class, () -> (cwd, sessionManager) -> request -> completedTurn(request, sessionManager))
            .withBean(SessionManagerFactoryPort.class, () -> this::sessionManager)
            .run(context -> assertThat(context).hasSingleBean(HeadlessSubagentCommand.class));
    }

    @Test
    void autoConfigurationCreatesHeadlessApplicationRunner() {
        new ApplicationContextRunner()
            .withUserConfiguration(HeadlessSubagentCommandAutoConfiguration.class)
            .run(context -> assertThat(context).hasSingleBean(HeadlessSubagentApplicationRunner.class));
    }

    @Test
    void applicationRunnerRunsHeadlessSubagentCommandWhenFlagIsPresent() {
        HeadlessSubagentJsonCodec codec = new HeadlessSubagentJsonCodec();
        ByteArrayOutputStream input = new ByteArrayOutputStream();
        codec.writeInput(new HeadlessSubagentInput(
            "ses_child",
            "ses_parent",
            "entry_spawn",
            "执行检查",
            tempDir,
            List.of(),
            PermissionMode.DEFAULT_EXECUTE,
            30
        ), input);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        HeadlessSubagentCommand command = new HeadlessSubagentCommand(
            (cwd, sessionManager) -> request -> completedTurn(request, sessionManager),
            (cwd, sessionId) -> {
                SessionManagerImpl manager = new SessionManagerImpl(cwd);
                manager.openOrCreate(sessionId);
                return manager;
            },
            codec
        );
        HeadlessSubagentApplicationRunner runner = new HeadlessSubagentApplicationRunner(
            () -> command,
            () -> new ByteArrayInputStream(input.toByteArray()),
            () -> output
        );

        runner.run(new DefaultApplicationArguments("--lypi.headless.subagent"));

        HeadlessSubagentOutput result = codec.readOutput(new ByteArrayInputStream(output.toByteArray()));
        assertThat(result.status()).isEqualTo(SubagentRunStatus.SUCCEEDED);
        assertThat(runner.getExitCode()).isZero();
    }

    @Test
    void applicationRunnerFailsFastWhenHeadlessFlagHasNoCommand() {
        HeadlessSubagentApplicationRunner runner = new HeadlessSubagentApplicationRunner(
            () -> null,
            ByteArrayInputStream::nullInputStream,
            ByteArrayOutputStream::new
        );

        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments("headless-subagent")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Headless subagent command is not available");
        assertThat(runner.getExitCode()).isEqualTo(2);
    }

    @Test
    void runWritesStructuredSubagentOutput() {
        SessionManagerImpl manager = new SessionManagerImpl(tempDir);
        HeadlessSubagentCommand command = new HeadlessSubagentCommand(
            (cwd, sessionManager) -> request -> completedTurn(request, sessionManager),
            (cwd, sessionId) -> {
                manager.openOrCreate(sessionId);
                return manager;
            },
            new HeadlessSubagentJsonCodec()
        );
        HeadlessSubagentJsonCodec codec = new HeadlessSubagentJsonCodec();
        ByteArrayOutputStream input = new ByteArrayOutputStream();
        codec.writeInput(new HeadlessSubagentInput(
            "ses_child",
            "ses_parent",
            "entry_spawn",
            "执行检查",
            tempDir,
            List.of(),
            PermissionMode.DEFAULT_EXECUTE,
            30
        ), input);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exitCode = command.run(new ByteArrayInputStream(input.toByteArray()), output);

        assertThat(exitCode).isEqualTo(0);
        HeadlessSubagentOutput result = codec.readOutput(new ByteArrayInputStream(output.toByteArray()));
        assertThat(result.childSessionId()).isEqualTo("ses_child");
        assertThat(result.status()).isEqualTo(SubagentRunStatus.SUCCEEDED);
        assertThat(result.summary()).isEqualTo("子任务完成");
        assertThat(result.finalEntryId()).isPresent();
    }

    private TurnState completedTurn(TurnRequest request) {
        return completedTurn(request, null);
    }

    private TurnState completedTurn(TurnRequest request, SessionManagerPort sessionManager) {
        AgentMessage assistant = new AgentMessage(
            "msg_assistant",
            MessageRole.ASSISTANT,
            MessageKind.TEXT,
            List.of(new TextContentBlock("子任务完成", Map.of())),
            Instant.EPOCH,
            Optional.empty(),
            Optional.empty()
        );
        if (sessionManager != null) {
            sessionManager.appendMessage(assistant);
        }
        return new TurnState(
            "turn_1",
            request.sessionId(),
            null,
            List.of(assistant),
            0,
            TurnStatus.COMPLETED
        );
    }

    private SessionManagerPort sessionManager(Path cwd, String sessionId) {
        SessionManagerImpl manager = new SessionManagerImpl(cwd);
        manager.openOrCreate(sessionId);
        return manager;
    }
}
