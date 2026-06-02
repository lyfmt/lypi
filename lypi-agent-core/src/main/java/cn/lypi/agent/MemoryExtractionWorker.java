package cn.lypi.agent;

import cn.lypi.contracts.agent.TurnState;

public interface MemoryExtractionWorker {
    /**
     * TODO: 在 turn 结束后提取长期记忆候选。
     *
     * 记忆写入失败不得导致当前 turn 失败，敏感信息不得写入记忆。
     */
    MemoryExtractionResult extractAfterTurn(TurnState state);
}
