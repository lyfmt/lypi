package cn.lypi.contracts.runtime;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.session.BranchSummaryPlan;
import cn.lypi.contracts.session.ForkRequest;
import cn.lypi.contracts.session.SessionContext;
import cn.lypi.contracts.session.SessionEntry;
import cn.lypi.contracts.session.SessionHandle;
import cn.lypi.contracts.session.SessionView;
import java.util.List;

public interface SessionManagerPort {
    /**
     * 打开或创建 session。
     *
     * NOTE: 读取 JSONL 后构建 entry 索引和当前 leaf，不得改写历史 entry。
     */
    SessionHandle openOrCreate(String sessionId);

    /**
     * 打开临时 session。
     *
     * NOTE: 临时 session 只有追加用户消息时才写入 JSONL。
     */
    default SessionHandle openTemporary(String sessionId) {
        return openOrCreate(sessionId);
    }

    /**
     * 追加 session entry。
     *
     * NOTE: 这是 session 的唯一写入口，必须保持 append-only。
     */
    SessionHandle append(SessionEntry entry);

    /**
     * 切换当前 session leaf。
     *
     * NOTE: 该操作只影响内存中的当前分支指针，不追加或改写 JSONL 历史。
     */
    SessionHandle switchLeaf(String leafId);

    /**
     * 查询 root-to-leaf 分支路径。
     */
    List<SessionEntry> branch(String leafId);

    /**
     * 收集从旧 leaf 离开时需要保留语义的路径后缀。
     *
     * NOTE: 返回的 entries 来自 oldLeafId 到目标路径共同祖先之间，不包含共同祖先。
     */
    BranchSummaryPlan collectBranchSummaryPlan(String oldLeafId, String targetLeafId);

    /**
     * 返回当前 session 最小视图。
     */
    SessionView currentView();

    /**
     * 返回指定 leaf 的最小视图。
     */
    SessionView view(String leafId);

    /**
     * 返回指定 leaf 的 transcript 消息序列。
     */
    List<AgentMessage> transcript(String leafId);

    /**
     * 返回指定 leaf 的 LLM session context。
     */
    SessionContext context(String leafId);

    /**
     * 追加一条消息 entry。
     *
     * NOTE: Typed helper 只能委托 append 语义，不得提供覆盖或更新能力。
     */
    SessionHandle appendMessage(AgentMessage message);

    /**
     * 在指定父节点后追加 branch summary 并移动当前 leaf。
     */
    SessionHandle appendBranchSummary(String parentId, String fromId, String summary);

    /**
     * fork 一个新 session。
     *
     * 从指定历史节点复制线性分支到新 session，并记录父 session 来源。
     */
    SessionHandle fork(ForkRequest request);
}
