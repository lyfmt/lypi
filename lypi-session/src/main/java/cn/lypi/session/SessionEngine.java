package cn.lypi.session;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.runtime.SessionEnginePort;
import cn.lypi.contracts.session.ForkRequest;
import cn.lypi.contracts.session.SessionEntry;
import cn.lypi.contracts.session.SessionHandle;
import java.util.List;

/**
 * 管理 session JSONL 历史和分支状态。
 *
 * NOTE: 该接口只负责历史事实读写，不构建 ContextSnapshot。
 */
public interface SessionEngine extends SessionEnginePort {
    /**
     * 打开已有 session 或创建新 session。
     */
    @Override
    SessionHandle openOrCreate(String sessionId);

    /**
     * 返回当前打开 session 的句柄。
     */
    SessionHandle current();

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
     * 返回从 leaf 到 root 的 entry 路径。
     */
    @Override
    List<SessionEntry> pathToRoot(String leafId);

    /**
     * 将 AgentMessage 包装为 MessageEntry 并追加。
     */
    @Override
    SessionHandle appendMessage(AgentMessage message);

    /**
     * 从指定 entry 派生一个新的 session。
     *
     * NOTE: fork 只复制 fork point 所在的线性路径，不复制 sibling branch。
     */
    @Override
    SessionHandle fork(ForkRequest request);
}
