package cn.lypi.contracts.runtime;

import java.util.List;

public interface CompactStateBackfillPort {
    /**
     * 返回 compact 后需要恢复到模型上下文的运行态回填项。
     *
     * NOTE: 实现方只提供稳定摘要，具体 session entry 追加由 agent-core 负责。
     */
    List<CompactStateBackfillItem> backfill(CompactStateBackfillRequest request);

    /**
     * 返回不产生任何回填项的端口实现。
     */
    static CompactStateBackfillPort none() {
        return request -> List.of();
    }
}
