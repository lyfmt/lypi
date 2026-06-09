package cn.lypi.tool.builtin.subagent;

import cn.lypi.contracts.runtime.MailboxPort;
import cn.lypi.contracts.subagent.MailboxCommandResult;

public final class DiscardMailboxMessageTool extends AbstractMailboxCommandTool {
    public DiscardMailboxMessageTool(MailboxPort mailbox) {
        super(mailbox);
    }

    @Override
    public String name() {
        return "discard_mailbox_message";
    }

    @Override
    public String description() {
        return "丢弃一条无需投递到当前 session 的 mailbox 消息。";
    }

    @Override
    protected MailboxCommandResult execute(MailboxPort mailbox, String sessionId, String mailId) {
        return mailbox.discard(sessionId, mailId);
    }

    @Override
    protected String progressPhase() {
        return "discarding";
    }

    @Override
    protected String progressMessage() {
        return "丢弃 mailbox 消息";
    }

    @Override
    protected String failureMessage() {
        return "丢弃 mailbox 消息失败。";
    }

    @Override
    protected String successTitle() {
        return "已丢弃 mailbox 消息。";
    }
}
