package cn.lypi.contracts.runtime;

import cn.lypi.contracts.subagent.MailboxCommandResult;
import cn.lypi.contracts.subagent.MailboxMessage;
import cn.lypi.contracts.subagent.MailboxStatus;
import java.util.List;
import java.util.Set;

public interface MailboxPort {
    /**
     * 查询指定 session 的 mailbox 消息。
     */
    List<MailboxMessage> read(String sessionId, Set<MailboxStatus> statuses);

    /**
     * 把 mailbox 消息追加到当前 session leaf。
     *
     * NOTE: 接收目标由调用方当前 leaf 决定，mailbox 不自行选择历史节点。
     */
    MailboxCommandResult accept(String sessionId, String mailId);

    /**
     * 暂存 mailbox 消息。
     */
    MailboxCommandResult stash(String sessionId, String mailId);

    /**
     * 丢弃 mailbox 消息。
     */
    MailboxCommandResult discard(String sessionId, String mailId);
}
