package cn.lypi.agent;

import cn.lypi.contracts.agent.TurnState;

public interface MemoryExtractionWorker {
    /**
     * 提取长期记忆候选的遗留同步挂点。
     *
     * NOTE: 默认 Boot 装配使用 NoopMemoryExtractionWorker；当前自动写入路径由
     * runtime 的 MemoryConsolidationTurnEndListener 在 turn end 后触发后台 hidden turn 承接。
     */
    MemoryExtractionResult extractAfterTurn(TurnState state);
}
