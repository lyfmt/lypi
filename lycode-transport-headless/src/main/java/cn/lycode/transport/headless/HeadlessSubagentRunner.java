package cn.lycode.transport.headless;

import cn.lycode.contracts.agent.TurnRequest;
import cn.lycode.contracts.agent.TurnState;
import cn.lycode.contracts.agent.TurnStatus;
import cn.lycode.contracts.common.AbortSignal;
import cn.lycode.contracts.context.AgentMessage;
import cn.lycode.contracts.context.ContentBlock;
import cn.lycode.contracts.context.TextContentBlock;
import cn.lycode.contracts.runtime.AgentCoreFactoryPort;
import cn.lycode.contracts.runtime.AgentCorePort;
import cn.lycode.contracts.runtime.SessionManagerFactoryPort;
import cn.lycode.contracts.runtime.SessionManagerPort;
import cn.lycode.contracts.subagent.HeadlessSubagentInput;
import cn.lycode.contracts.subagent.HeadlessSubagentOutput;
import cn.lycode.contracts.subagent.SubagentRunStatus;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class HeadlessSubagentRunner {
    private final AgentCoreFactoryPort agentCoreFactory;
    private final SessionManagerFactoryPort sessionManagerFactory;
    private final HeadlessSubagentJsonCodec codec;

    public HeadlessSubagentRunner(
        AgentCoreFactoryPort agentCoreFactory,
        SessionManagerFactoryPort sessionManagerFactory,
        HeadlessSubagentJsonCodec codec
    ) {
        this.agentCoreFactory = Objects.requireNonNull(agentCoreFactory, "agentCoreFactory must not be null");
        this.sessionManagerFactory = Objects.requireNonNull(sessionManagerFactory, "sessionManagerFactory must not be null");
        this.codec = codec == null ? new HeadlessSubagentJsonCodec() : codec;
    }

    public void run(InputStream in, OutputStream out) {
        HeadlessSubagentOutput output;
        try {
            output = execute(codec.readInput(in));
        } catch (RuntimeException exception) {
            output = failure(null, exception.getMessage());
        }
        codec.writeOutput(output, out);
    }

    public HeadlessSubagentOutput execute(HeadlessSubagentInput input) {
        validate(input);
        try {
            SessionManagerPort childSessionManager = sessionManagerFactory.open(input.sessionCwd(), input.childSessionId());
            AgentCorePort agentCore = agentCoreFactory.create(input.cwd(), childSessionManager, input.toolPolicy());
            TurnState state = agentCore.execute(new TurnRequest(
                input.childSessionId(),
                input.message(),
                Optional.empty(),
                neverAborted(),
                TurnRequest.DEFAULT_MAX_TOOL_ROUNDS,
                List.of()
            ));
            SubagentRunStatus status = status(state.status());
            String content = content(state);
            return new HeadlessSubagentOutput(
                input.taskName(),
                input.agentId(),
                input.childSessionId(),
                input.runId(),
                status,
                content,
                finalEntryId(childSessionManager),
                failureMessage(status, state.status(), content)
            );
        } catch (RuntimeException exception) {
            return failure(input, exception.getMessage());
        }
    }

    private void validate(HeadlessSubagentInput input) {
        if (input == null) {
            throw new IllegalArgumentException("Headless subagent input is required");
        }
        require(input.taskName(), "taskName");
        require(input.agentId(), "agentId");
        require(input.childSessionId(), "childSessionId");
        require(input.runId(), "runId");
        require(input.parentSessionId(), "parentSessionId");
        require(input.parentSpawnEntryId(), "parentSpawnEntryId");
        require(input.message(), "message");
        if (input.cwd() == null || input.sessionCwd() == null) {
            throw new IllegalArgumentException("cwd and sessionCwd are required");
        }
    }

    private void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    private HeadlessSubagentOutput failure(HeadlessSubagentInput input, String errorMessage) {
        return new HeadlessSubagentOutput(
            input == null ? "" : input.taskName(),
            input == null ? "" : input.agentId(),
            input == null ? "" : input.childSessionId(),
            input == null ? "" : input.runId(),
            SubagentRunStatus.FAILED,
            "",
            Optional.empty(),
            Optional.ofNullable(errorMessage)
        );
    }

    private AbortSignal neverAborted() {
        return () -> false;
    }

    private SubagentRunStatus status(TurnStatus status) {
        if (status == TurnStatus.COMPLETED) {
            return SubagentRunStatus.SUCCEEDED;
        }
        if (status == TurnStatus.ABORTED) {
            return SubagentRunStatus.INTERRUPTED;
        }
        return SubagentRunStatus.FAILED;
    }

    private Optional<String> failureMessage(SubagentRunStatus runStatus, TurnStatus turnStatus, String content) {
        if (runStatus == SubagentRunStatus.SUCCEEDED) {
            return Optional.empty();
        }
        String base = "Child turn ended with " + turnStatus;
        return content.isBlank() ? Optional.of(base) : Optional.of(base + ": " + content);
    }

    private String content(TurnState state) {
        List<AgentMessage> messages = state.newMessages();
        if (messages == null || messages.isEmpty() || messages.getLast().content() == null) {
            return "";
        }
        return messages.getLast().content().stream()
            .map(this::text)
            .filter(text -> !text.isBlank())
            .findFirst()
            .orElse("");
    }

    private Optional<String> finalEntryId(SessionManagerPort childSessionManager) {
        String leafId = childSessionManager.currentView().leafId();
        return leafId == null || leafId.isBlank() ? Optional.empty() : Optional.of(leafId);
    }

    private String text(ContentBlock block) {
        return block instanceof TextContentBlock text ? text.text() : "";
    }
}
