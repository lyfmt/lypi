package cn.lypi.contracts.runtime;

public interface CompactionRuntimePort {
    /**
     * 手动触发一次 session compaction。
     *
     * 调用方负责传入当前 session、leaf 和 cwd；实现方负责判断是否有可执行压缩计划。
     */
    CompactionResult compact(CompactionRequest request);
}
