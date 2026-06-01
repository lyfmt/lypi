package cn.lypi.session;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.runtime.SessionEnginePort;
import cn.lypi.contracts.session.ForkRequest;
import cn.lypi.contracts.session.SessionEntry;
import cn.lypi.contracts.session.SessionHandle;
import java.util.List;

public interface SessionEngine extends SessionEnginePort {
    /*
    * @status : 未完成
    * @summary : 打开或创建 session。
    *@description : 读取 JSONL 后构建 entry 索引和当前 leaf，不得改写历史 entry。
    *
    *
                              */
    SessionHandle openOrCreate(String sessionId);

    /*
    * @status : 未完成
    * @summary : 追加 session entry。
    *@description : 这是 session 的唯一写入口，必须保持 append-only。
    *
    *
                              */
    SessionHandle append(SessionEntry entry);

    /*
    * @status : 未完成
    * @summary : 查询从 leaf 到 root 的分支路径。
    *@description : ContextAssembler 使用该路径构建当前上下文视图。
    *
    *
                              */
    List<SessionEntry> pathToRoot(String leafId);

    /*
    * @status : 未完成
    * @summary : 追加一条消息 entry。
    *@description : typed helper 只能委托 append 语义，不得提供覆盖或更新能力。
    *
    *
                              */
    SessionHandle appendMessage(AgentMessage message);

    /*
    * @status : 未完成
    * @summary : fork 一个新 session。
    *@description : 从指定历史节点复制线性分支到新 session，并记录父 session 来源。
    *
    *
                              */
    SessionHandle fork(ForkRequest request);
}

