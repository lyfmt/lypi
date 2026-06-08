package cn.lypi.transport.headless;

import cn.lypi.contracts.agent.TurnRequest;
import cn.lypi.contracts.agent.TurnState;
import cn.lypi.contracts.agent.TurnStatus;
import cn.lypi.contracts.common.AbortSignal;
import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContentBlock;
import cn.lypi.contracts.context.TextContentBlock;
import cn.lypi.contracts.runtime.AgentCorePort;
import cn.lypi.contracts.runtime.SessionManagerFactoryPort;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.subagent.HeadlessSubagentInput;
import cn.lypi.contracts.subagent.HeadlessSubagentOutput;
import cn.lypi.contracts.subagent.SubagentRunStatus;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class HeadlessSubagentRunner {
    private final AgentCorePort agentCore;
    private final SessionManagerFactoryPort sessionManagerFactory;
    private final HeadlessSubagentJsonCodec codec;

    public HeadlessSubagentRunner(
        AgentCorePort agentCore,
        SessionManagerFactoryPort sessionManagerFactory,
        HeadlessSubagentJsonCodec codec
    ) {
        this.agentCore = Objects.requireNonNull(agentCore, "agentCore must not be null");
        this.sessionManagerFactory = Objects.requireNonNull(sessionManagerFactory, "sessionManagerFactory must not be null");
        this.codec = codec == null ? new HeadlessSubagentJsonCodec() : codec;
    }

    /**
     * 执行一次 headless subagent JSON 请求。
     *
     * NOTE: stdout 只写结构化 JSON；诊断日志应由调用方写 stderr。
     */
    public void run(InputStream in, OutputStream out) {
        HeadlessSubagentOutput output;
        try {
            output = execute(codec.readInput(in));
        } catch (RuntimeException e) {
            output = failure("", e.getMessage());
        }
        codec.writeOutput(output, out);
    }

    /**
     * 执行已解析的 headless subagent 输入。
     */
    public HeadlessSubagentOutput execute(HeadlessSubagentInput input) {
        validate(input);
        try {
            sessionManagerFactory.open(input.cwd(), input.childSessionId());
            TurnState state = agentCore.execute(new TurnRequest(
                input.childSessionId(),
                input.prompt(),
                Optional.empty(),
                neverAborted()
            ));
            SubagentRunStatus status = status(state.status());
            return new HeadlessSubagentOutput(
                input.childSessionId(),
                status,
                summary(state),
                finalEntryId(state),
                status == SubagentRunStatus.SUCCEEDED ? Optional.empty() : Optional.of("Child turn ended with " + state.status())
            );
        } catch (RuntimeException e) {
            return failure(input.childSessionId(), e.getMessage());
        }
    }

    private void validate(HeadlessSubagentInput input) {
        if (input == null) {
            throw new IllegalArgumentException("Headless subagent input is required");
        }
        if (blank(input.childSessionId())) {
            throw new IllegalArgumentException("childSessionId is required");
        }
        if (blank(input.parentSessionId())) {
            throw new IllegalArgumentException("parentSessionId is required");
        }
        if (blank(input.parentSpawnEntryId())) {
            throw new IllegalArgumentException("parentSpawnEntryId is required");
        }
        if (blank(input.prompt())) {
            throw new IllegalArgumentException("prompt is required");
        }
        if (input.cwd() == null) {
            throw new IllegalArgumentException("cwd is required");
        }
        if (!input.allowedTools().isEmpty()) {
            throw new IllegalArgumentException("allowedTools is not supported yet");
        }
        if (input.permissionMode() != PermissionMode.DEFAULT_EXECUTE) {
            throw new IllegalArgumentException("permissionMode override is not supported yet");
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private HeadlessSubagentOutput failure(String childSessionId, String errorMessage) {
        return new HeadlessSubagentOutput(
            childSessionId == null ? "" : childSessionId,
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

    private String summary(TurnState state) {
        List<AgentMessage> messages = state.newMessages();
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        AgentMessage message = messages.getLast();
        if (message.content() == null) {
            return "";
        }
        return message.content().stream()
            .map(this::text)
            .filter(text -> !text.isBlank())
            .findFirst()
            .orElse("");
    }

    private Optional<String> finalEntryId(TurnState state) {
        List<AgentMessage> messages = state.newMessages();
        if (messages == null || messages.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(messages.getLast().id());
    }

    private String text(ContentBlock block) {
        if (block instanceof TextContentBlock text) {
            return text.text();
        }
        return "";
    }
}
