package cn.lypi.tool.builtin.subagent;

import cn.lypi.contracts.common.JsonSchema;
import cn.lypi.contracts.common.ProgressSink;
import cn.lypi.contracts.common.ToolProgress;
import cn.lypi.contracts.common.ValidationResult;
import cn.lypi.contracts.runtime.MailboxPort;
import cn.lypi.contracts.subagent.MailboxCommandResult;
import cn.lypi.contracts.subagent.MailboxMessage;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseContext;
import java.util.List;
import java.util.Map;
import java.util.Objects;

abstract class AbstractMailboxCommandTool extends AbstractSubagentTool {
    private final MailboxPort mailbox;

    AbstractMailboxCommandTool(MailboxPort mailbox) {
        this.mailbox = Objects.requireNonNull(mailbox, "mailbox must not be null");
    }

    @Override
    public JsonSchema inputSchema() {
        return new JsonSchema(Map.of(
            "type", "object",
            "required", List.of("mailId"),
            "properties", Map.of(
                "mailId", Map.of("type", "string")
            )
        ));
    }

    @Override
    public ValidationResult validateInput(Map<String, Object> input, ToolUseContext context) {
        return requireAny(input, "mailId", "mail_id");
    }

    @Override
    public ToolResult<String> execute(Map<String, Object> input, ToolUseContext context, ProgressSink progress) {
        progress.progress(ToolProgress.phase(progressPhase(), progressMessage()));
        String mailId = stringInput(input, "mailId", "mail_id");
        MailboxCommandResult result = execute(mailbox, context.sessionId(), mailId);
        if (!result.success()) {
            return error(context, result.errorMessage().orElse(failureMessage()));
        }
        if (result.message().isEmpty()) {
            return success(context, simpleSuccessMessage(mailId));
        }
        return success(context, detailedSuccessMessage(result.message().get()));
    }

    @Override
    public boolean isReadOnly(Map<String, Object> input) {
        return false;
    }

    protected abstract MailboxCommandResult execute(MailboxPort mailbox, String sessionId, String mailId);

    protected abstract String progressPhase();

    protected abstract String progressMessage();

    protected abstract String failureMessage();

    protected abstract String successTitle();

    private String simpleSuccessMessage(String mailId) {
        return trimTrailingSentencePunctuation(successTitle()) + ": " + mailId;
    }

    private String detailedSuccessMessage(MailboxMessage message) {
        return """
            %s
            mailId: %s
            childSessionId: %s
            status: %s
            summary: %s
            """.formatted(
                successTitle(),
                message.mailId(),
                message.childSessionId(),
                message.status(),
                message.summary()
            ).trim();
    }

    private String trimTrailingSentencePunctuation(String value) {
        if (value.endsWith("。") || value.endsWith(".")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }
}
