package cn.lypi.contracts.runtime;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.session.ForkRequest;
import cn.lypi.contracts.session.SessionEntry;
import cn.lypi.contracts.session.SessionHandle;
import java.util.List;

public interface SessionEnginePort {
    /**
     * 打开或创建 session。
     *
     * NOTE: 读取 JSONL 后构建 entry 索引和当前 leaf，不得改写历史 entry。
     */
    SessionHandle openOrCreate(String sessionId);

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
     * 查询从 leaf 到 root 的分支路径。
     *
     * ContextAssembler 使用该路径构建当前上下文视图。
     */
    List<SessionEntry> pathToRoot(String leafId);

    /**
     * 追加一条消息 entry。
     *
     * NOTE: Typed helper 只能委托 append 语义，不得提供覆盖或更新能力。
     */
    SessionHandle appendMessage(AgentMessage message);

    /**
     * fork 一个新 session。
     *
     * 从指定历史节点复制线性分支到新 session，并记录父 session 来源。
     */
    SessionHandle fork(ForkRequest request);
}
