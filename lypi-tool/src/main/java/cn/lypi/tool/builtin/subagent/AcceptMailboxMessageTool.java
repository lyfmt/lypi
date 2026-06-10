package cn.lypi.tool.builtin.subagent;

import cn.lypi.contracts.runtime.MailboxPort;
import cn.lypi.contracts.subagent.MailboxCommandResult;

public final class AcceptMailboxMessageTool extends AbstractMailboxCommandTool {
    public AcceptMailboxMessageTool(MailboxPort mailbox) {
        super(mailbox);
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
    protected MailboxCommandResult execute(MailboxPort mailbox, String sessionId, String mailId) {
        return mailbox.accept(sessionId, mailId);
    }

    @Override
    protected String progressPhase() {
        return "accepting";
    }

    @Override
    protected String progressMessage() {
        return "接收 mailbox 消息";
    }

    @Override
    protected String failureMessage() {
        return "接收 mailbox 消息失败。";
    }

    @Override
    protected String successTitle() {
        return "已接收 mailbox 消息。";
    }
}
