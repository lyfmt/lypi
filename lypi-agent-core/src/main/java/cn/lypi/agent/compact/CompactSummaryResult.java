package cn.lypi.agent.compact;

import cn.lypi.contracts.model.TokenUsage;
import java.util.Objects;

/**
 * 表示 compact summary 模型调用结果。
 *
 * usage 仅表示 summary 调用本身的消耗，不代表 compact 后上下文大小。
 */
public record CompactSummaryResult(
    String summary,
    TokenUsage usage
) {
    public CompactSummaryResult {
        if (summary == null || summary.isBlank()) {
            throw new IllegalArgumentException("summary must not be blank");
        }
        usage = Objects.requireNonNull(usage, "usage");
    }
}
