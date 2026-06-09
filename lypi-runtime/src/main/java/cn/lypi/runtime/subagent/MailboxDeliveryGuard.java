package cn.lypi.runtime.subagent;

import cn.lypi.contracts.subagent.MailboxMessage;

@FunctionalInterface
public interface MailboxDeliveryGuard {
    /**
     * 判断 mailbox 消息是否允许自动投递。
     */
    boolean canDeliver(MailboxMessage message);
}
