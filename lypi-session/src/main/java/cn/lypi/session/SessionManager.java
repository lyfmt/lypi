package cn.lypi.session;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.runtime.SessionManagerPort;
import cn.lypi.contracts.session.BranchSummaryPlan;
import cn.lypi.contracts.session.ForkRequest;
import cn.lypi.contracts.session.SessionContext;
import cn.lypi.contracts.session.SessionEntry;
import cn.lypi.contracts.session.SessionHandle;
import cn.lypi.contracts.session.SessionView;
import cn.lypi.contracts.tui.SessionFileView;
import java.util.List;

/**
 * 管理 session JSONL 历史和分支状态。
 *
 * NOTE: 该接口只负责 session tree 与 replay，不构建 system prompt 或 budget。
 */
public interface SessionManager extends SessionManagerPort {
    /**
     * 打开已有 session 或创建新 session。
     */
    @Override
    SessionHandle openOrCreate(String sessionId);

    /**
     * 打开临时 session。
     *
     * NOTE: 临时 session 只有追加用户消息时才写入 JSONL。
     */
    @Override
    SessionHandle openTemporary(String sessionId);

    /**
     * 追加一条 session entry。
     *
     * NOTE: entry 必须满足 append-only 约束，不能复用已有 id。
     */
    @Override
    SessionHandle append(SessionEntry entry);

    /**
     * 切换当前 leaf。
     *
     * NOTE: 切换 leaf 只改变内存状态，不追加 JSONL 行。
     */
    @Override
    SessionHandle switchLeaf(String leafId);

    /**
     * 返回 root-to-leaf 的 entry 路径。
     */
    @Override
    List<SessionEntry> branch(String leafId);

    /**
     * 收集从旧 leaf 离开时需要总结的旧路径后缀。
     */
    @Override
    BranchSummaryPlan collectBranchSummaryPlan(String oldLeafId, String targetLeafId);

    /**
     * 返回当前 session 最小视图。
     */
    @Override
    SessionView currentView();

    /**
     * 返回指定 leaf 的最小视图。
     */
    @Override
    SessionView view(String leafId);

    /**
     * 返回指定 leaf 的 transcript 消息序列。
     */
    @Override
    List<AgentMessage> transcript(String leafId);

    /**
     * 从指定 branch 的 assistant tool call 派生文件操作视图。
     */
    List<SessionFileView> files(String leafId);

    /**
     * 返回指定 leaf 的 LLM session context。
     */
    @Override
    SessionContext context(String leafId);

    /**
     * 将 AgentMessage 包装为 MessageEntry 并追加。
     */
    @Override
    SessionHandle appendMessage(AgentMessage message);

    /**
     * 追加 branch summary entry。
     */
    @Override
    SessionHandle appendBranchSummary(String parentId, String fromId, String summary);

    /**
     * 从指定 entry 派生一个新的 session。
     *
     * NOTE: fork 只复制 fork point 所在的线性路径，不复制 sibling branch。
     */
    @Override
    SessionHandle fork(ForkRequest request);
}
