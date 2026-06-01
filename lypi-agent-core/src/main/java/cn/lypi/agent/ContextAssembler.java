package cn.lypi.agent;

import cn.lypi.contracts.context.ContextSnapshot;

public interface ContextAssembler {
    /*
    * @status : 未完成
    * @summary : 构建模型上下文快照。
    *@description : 从 session 分支路径恢复消息、模式、权限、压缩摘要和预算视图，不改写 transcript。
    *
    *
                              */
    ContextSnapshot build(String leafId);
}

