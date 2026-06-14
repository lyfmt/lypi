package cn.lypi.runtime.memory;

/**
 * 执行一次后台记忆沉淀。
 */
@FunctionalInterface
public interface MemoryConsolidationRunner {
    /**
     * 使用已捕获的主 session 和 fork point 发起沉淀。
     */
    void run(MemoryConsolidationRequest request);
}
