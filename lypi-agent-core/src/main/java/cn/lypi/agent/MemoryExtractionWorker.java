package cn.lypi.agent;

import cn.lypi.contracts.agent.TurnState;

public interface MemoryExtractionWorker {
    /*
    * @status : 未完成
    * @summary : 在 turn 结束后提取长期记忆候选。
    *@description : 记忆写入失败不得导致当前 turn 失败，敏感信息不得写入记忆。
    *
    *
                              */
    void extractAfterTurn(TurnState state);
}

