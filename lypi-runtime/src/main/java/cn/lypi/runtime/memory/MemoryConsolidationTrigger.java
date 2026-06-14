package cn.lypi.runtime.memory;

import cn.lypi.contracts.event.TurnEndEvent;

/**
 * 判断 turn 结束后是否需要后台记忆沉淀。
 */
public record MemoryConsolidationTrigger(long minDurationMillis, int minToolRounds) {
    public static final long DEFAULT_MIN_DURATION_MILLIS = 20L * 60L * 1000L;
    public static final int DEFAULT_MIN_TOOL_ROUNDS = 30;

    public MemoryConsolidationTrigger() {
        this(DEFAULT_MIN_DURATION_MILLIS, DEFAULT_MIN_TOOL_ROUNDS);
    }

    /**
     * 返回是否应为本次 turn 发起后台沉淀。
     *
     * NOTE: 后台沉淀 turn 自身不能递归触发沉淀。
     */
    public boolean shouldTrigger(TurnEndEvent event, boolean consolidationSession) {
        if (event == null || consolidationSession) {
            return false;
        }
        if (!"COMPLETED".equalsIgnoreCase(event.status())) {
            return false;
        }
        return event.durationMillis() >= minDurationMillis || event.toolRounds() > minToolRounds;
    }
}
