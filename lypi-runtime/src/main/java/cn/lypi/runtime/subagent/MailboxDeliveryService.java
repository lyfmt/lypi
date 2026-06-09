package cn.lypi.runtime.subagent;

import cn.lypi.contracts.subagent.MailboxMessage;

public final class MailboxDeliveryService {
    private final DefaultMailboxService mailbox;
    private final MailboxDeliveryGuard guard;

    public MailboxDeliveryService(DefaultMailboxService mailbox, MailboxDeliveryGuard guard) {
        this.mailbox = mailbox;
        this.guard = guard == null ? ignored -> false : guard;
    }

    /**
     * 在 guard 允许时自动投递 mailbox 消息。
     */
    public void tryDeliver(MailboxMessage message) {
        if (guard.canDeliver(message)) {
            mailbox.accept(message.parentSessionId(), message.mailId());
        }
    }
}
