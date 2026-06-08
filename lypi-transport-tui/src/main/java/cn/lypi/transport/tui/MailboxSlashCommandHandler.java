package cn.lypi.transport.tui;

import cn.lypi.contracts.prompt.PromptParameter;
import cn.lypi.contracts.runtime.MailboxPort;
import cn.lypi.contracts.subagent.MailboxCommandResult;
import cn.lypi.contracts.subagent.MailboxMessage;
import cn.lypi.contracts.subagent.MailboxStatus;
import cn.lypi.contracts.tui.SlashCommand;
import cn.lypi.contracts.tui.SlashCommandHandler;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public final class MailboxSlashCommandHandler implements SlashCommandHandler {
    private final MailboxPort mailbox;
    private final Supplier<String> currentSessionId;
    private String lastOutput = "";

    public MailboxSlashCommandHandler(MailboxPort mailbox, Supplier<String> currentSessionId) {
        this.mailbox = Objects.requireNonNull(mailbox, "mailbox must not be null");
        this.currentSessionId = Objects.requireNonNull(currentSessionId, "currentSessionId must not be null");
    }

    /**
     * 返回 /mailbox slash command 定义。
     */
    public SlashCommand command() {
        return new SlashCommand(
            "mailbox",
            "读取或处理 subagent mailbox 消息。",
            List.of(
                new PromptParameter("action", "list、accept、stash 或 discard。", false, Optional.of("list")),
                new PromptParameter("mailId", "要处理的 mailbox 消息 id。", false, Optional.empty()),
                new PromptParameter("statuses", "list 时筛选状态，逗号分隔。", false, Optional.of("PENDING"))
            ),
            this
        );
    }

    @Override
    public void handle(Map<String, String> arguments) {
        Map<String, String> safeArguments = arguments == null ? Map.of() : arguments;
        String action = action(safeArguments);
        switch (action) {
            case "list" -> list(safeArguments);
            case "accept" -> command(safeArguments, mailbox::accept, "已接收 mailbox 消息。");
            case "stash" -> command(safeArguments, mailbox::stash, "已暂存 mailbox 消息。");
            case "discard" -> command(safeArguments, mailbox::discard, "已丢弃 mailbox 消息。");
            default -> lastOutput = "未知 mailbox action: " + action;
        }
    }

    /**
     * 返回最近一次 slash command 的用户可见输出。
     */
    public String lastOutput() {
        return lastOutput;
    }

    private String action(Map<String, String> arguments) {
        String action = value(arguments, "action");
        if (!action.isBlank()) {
            return action.toLowerCase(Locale.ROOT);
        }
        return value(arguments, "mailId").isBlank() ? "list" : "accept";
    }

    private void list(Map<String, String> arguments) {
        List<MailboxMessage> messages = mailbox.read(currentSessionId.get(), statuses(arguments));
        if (messages.isEmpty()) {
            lastOutput = "Mailbox 当前没有匹配消息。";
            return;
        }
        lastOutput = messages.stream()
            .map(this::render)
            .collect(Collectors.joining("\n\n"));
    }

    private void command(Map<String, String> arguments, MailboxCommand command, String successMessage) {
        String mailId = value(arguments, "mailId");
        if (mailId.isBlank()) {
            lastOutput = "mailId 不能为空。";
            return;
        }
        MailboxCommandResult result = command.apply(currentSessionId.get(), mailId);
        if (!result.success()) {
            lastOutput = result.errorMessage().orElse("mailbox 命令执行失败。");
            return;
        }
        if (result.message().isEmpty()) {
            lastOutput = successMessage + "\nmailId: " + mailId;
            return;
        }
        MailboxMessage message = result.message().get();
        lastOutput = """
            %s
            mailId: %s
            childSessionId: %s
            status: %s
            summary: %s
            """.formatted(
                successMessage,
                message.mailId(),
                message.childSessionId(),
                message.status(),
                message.summary()
            ).trim();
    }

    private Set<MailboxStatus> statuses(Map<String, String> arguments) {
        String statuses = value(arguments, "statuses");
        if (statuses.isBlank()) {
            return Set.of(MailboxStatus.PENDING);
        }
        EnumSet<MailboxStatus> parsed = EnumSet.noneOf(MailboxStatus.class);
        for (String status : statuses.split(",")) {
            String normalized = status.trim();
            if (!normalized.isBlank()) {
                parsed.add(MailboxStatus.valueOf(normalized.toUpperCase(Locale.ROOT)));
            }
        }
        return Set.copyOf(parsed);
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

    private String value(Map<String, String> arguments, String name) {
        String value = arguments.get(name);
        return value == null ? "" : value.trim();
    }

    @FunctionalInterface
    private interface MailboxCommand {
        MailboxCommandResult apply(String sessionId, String mailId);
    }
}
