package cn.lypi.boot.headless;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.agent.TurnRequest;
import cn.lypi.contracts.agent.TurnState;
import cn.lypi.contracts.agent.TurnStatus;
import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.TextContentBlock;
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
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class HeadlessSubagentCommandTest {
    @TempDir
    Path tempDir;

    @Test
    void autoConfigurationCreatesHeadlessSubagentCommandWhenDependenciesExist() {
        new ApplicationContextRunner()
            .withUserConfiguration(HeadlessSubagentCommandAutoConfiguration.class)
            .withBean(AgentCorePort.class, () -> this::completedTurn)
            .withBean(SessionManagerFactoryPort.class, () -> this::sessionManager)
            .run(context -> assertThat(context).hasSingleBean(HeadlessSubagentCommand.class));
    }

    @Test
    void runWritesStructuredSubagentOutput() {
        SessionManagerImpl manager = new SessionManagerImpl(tempDir);
        HeadlessSubagentCommand command = new HeadlessSubagentCommand(
            request -> completedTurn(request, manager),
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
