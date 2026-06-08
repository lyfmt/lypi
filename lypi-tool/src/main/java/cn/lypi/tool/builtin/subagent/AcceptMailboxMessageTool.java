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

public final class AcceptMailboxMessageTool extends AbstractSubagentTool {
    private final MailboxPort mailbox;

    public AcceptMailboxMessageTool(MailboxPort mailbox) {
        this.mailbox = Objects.requireNonNull(mailbox, "mailbox must not be null");
    }

    @Override
    public String name() {
        return "accept_mailbox_message";
    }

    @Override
    public String description() {
        return "接收一条 mailbox 消息并追加到当前 session leaf。";
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
        progress.progress(ToolProgress.phase("accepting", "接收 mailbox 消息"));
        String mailId = stringInput(input, "mailId", "mail_id");
        MailboxCommandResult result = mailbox.accept(context.sessionId(), mailId);
        if (!result.success()) {
            return error(context, result.errorMessage().orElse("接收 mailbox 消息失败。"));
        }
        if (result.message().isEmpty()) {
            return success(context, "已接收 mailbox 消息: " + mailId);
        }
        MailboxMessage message = result.message().get();
        return success(context, """
            已接收 mailbox 消息。
            mailId: %s
            childSessionId: %s
            status: %s
            summary: %s
            """.formatted(
                message.mailId(),
                message.childSessionId(),
                message.status(),
                message.summary()
            ).trim());
    }

    @Override
    public boolean isReadOnly(Map<String, Object> input) {
        return false;
    }
}
