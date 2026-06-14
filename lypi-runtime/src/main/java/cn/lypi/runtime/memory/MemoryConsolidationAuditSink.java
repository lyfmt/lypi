package cn.lypi.runtime.memory;

/**
 * 写入后台记忆沉淀审计记录。
 */
public interface MemoryConsolidationAuditSink {
    /**
     * 保存一条审计记录。
     *
     * NOTE: 实现不得把写入失败传播到主 turn 或后台沉淀流程。
     */
    void record(MemoryConsolidationAuditRecord record);

    /**
     * 返回丢弃所有审计记录的 sink。
     */
    static MemoryConsolidationAuditSink noop() {
        return record -> {
        };
    }
}
