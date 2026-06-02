package cn.lypi.agent;

import cn.lypi.contracts.context.ContextSnapshot;

public interface CompactionCoordinator {
    /**
     * 执行上下文压缩预检查。
     *
     * 当上下文超预算时生成压缩计划和摘要；失败时回退到未压缩上下文。
     */
    CompactionDecision preflight(ContextSnapshot context);
}
