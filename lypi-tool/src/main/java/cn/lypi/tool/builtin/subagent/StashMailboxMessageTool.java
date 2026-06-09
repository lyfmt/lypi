package cn.lypi.tool.builtin.subagent;

import cn.lypi.contracts.runtime.MailboxPort;
import cn.lypi.contracts.subagent.MailboxCommandResult;

public final class StashMailboxMessageTool extends AbstractMailboxCommandTool {
    public StashMailboxMessageTool(MailboxPort mailbox) {
        super(mailbox);
    }

    @Override
    public String name() {
        return "stash_mailbox_message";
    }

    @Override
    public String description() {
        return "暂存一条 mailbox 消息，稍后再处理。";
    }

    @Override
    protected MailboxCommandResult execute(MailboxPort mailbox, String sessionId, String mailId) {
        return mailbox.stash(sessionId, mailId);
    }

    @Override
    protected String progressPhase() {
        return "stashing";
    }

    @Override
    protected String progressMessage() {
        return "暂存 mailbox 消息";
    }

    @Override
    protected String failureMessage() {
        return "暂存 mailbox 消息失败。";
    }

    @Override
    protected String successTitle() {
        return "已暂存 mailbox 消息。";
    }
}
