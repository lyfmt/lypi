package cn.lypi.tool.builtin.subagent;

import cn.lypi.contracts.common.JsonSchema;
import cn.lypi.contracts.common.ProgressSink;
import cn.lypi.contracts.common.ToolProgress;
import cn.lypi.contracts.runtime.MailboxPort;
import cn.lypi.contracts.subagent.MailboxMessage;
import cn.lypi.contracts.subagent.MailboxStatus;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseContext;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class ReadMailboxTool extends AbstractSubagentTool {
    private final MailboxPort mailbox;

    public ReadMailboxTool(MailboxPort mailbox) {
        this.mailbox = Objects.requireNonNull(mailbox, "mailbox must not be null");
    }

    @Override
    public String name() {
        return "read_mailbox";
    }

    @Override
    public String description() {
        return "读取当前 session 的 mailbox 消息。";
    }

    @Override
    public JsonSchema inputSchema() {
        return new JsonSchema(Map.of(
            "type", "object",
            "properties", Map.of(
                "statuses", Map.of("type", "array", "items", Map.of("type", "string"))
            )
        ));
    }

    @Override
    public ToolResult<String> execute(Map<String, Object> input, ToolUseContext context, ProgressSink progress) {
        try {
            progress.progress(ToolProgress.phase("reading", "读取 mailbox"));
            List<MailboxMessage> messages = mailbox.read(context.sessionId(), statuses(input));
            if (messages.isEmpty()) {
                return success(context, "Mailbox 当前没有匹配消息。");
            }
            return success(context, messages.stream()
                .map(this::render)
                .collect(Collectors.joining("\n\n")));
        } catch (IllegalArgumentException exception) {
            return error(context, exception.getMessage());
        }
    }

    @Override
    public boolean isReadOnly(Map<String, Object> input) {
        return true;
    }

    private Set<MailboxStatus> statuses(Map<String, Object> input) {
        List<String> rawStatuses = stringListInput(input, "statuses");
        if (rawStatuses.isEmpty()) {
            return Set.of(MailboxStatus.PENDING);
        }
        EnumSet<MailboxStatus> statuses = EnumSet.noneOf(MailboxStatus.class);
        for (String status : rawStatuses) {
            statuses.add(MailboxStatus.valueOf(status.trim().toUpperCase(Locale.ROOT)));
        }
        return Set.copyOf(statuses);
    }

    private String render(MailboxMessage message) {
        return """
            mailId: %s
            agentId: %s
            childSessionId: %s
            status: %s
            summary: %s
            finalEntryId: %s
            """.formatted(
                message.mailId(),
                message.agentId(),
                message.childSessionId(),
                message.status(),
                message.summary(),
                message.contentRef().finalEntryId()
            ).trim();
    }
}
