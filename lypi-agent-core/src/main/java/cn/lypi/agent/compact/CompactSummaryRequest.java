package cn.lypi.agent.compact;

import cn.lypi.contracts.common.AbortSignal;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.session.CompactionPlan;
import cn.lypi.contracts.session.SessionEntry;
import java.util.List;
import java.util.Objects;

/**
 * 表示一次 compact 摘要生成请求。
 *
 * NOTE: context.messages() 是 AI 摘要请求的主体消息前缀；plan 和 branchEntries
 * 只用于边界记录、审计和调试，不要求模型根据 entry id 判断摘要范围。
 */
public record CompactSummaryRequest(
    ContextSnapshot context,
    CompactionPlan plan,
    List<SessionEntry> branchEntries,
    AbortSignal abortSignal
) {
    public CompactSummaryRequest {
        context = Objects.requireNonNull(context, "context");
        plan = Objects.requireNonNull(plan, "plan");
        branchEntries = List.copyOf(Objects.requireNonNull(branchEntries, "branchEntries"));
        abortSignal = Objects.requireNonNull(abortSignal, "abortSignal");
    }
}
