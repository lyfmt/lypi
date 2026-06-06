package cn.lypi.agent.compact;

import cn.lypi.contracts.common.AbortSignal;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.session.CompactionPlan;
import cn.lypi.contracts.session.SessionEntry;
import java.util.List;
import java.util.Objects;

/**
 * 表示一次 compact summary 模型调用请求。
 *
 * NOTE: context.messages() 是 summary 请求主体消息前缀；branchEntries 仅用于审计、
 * fallback 和 compact 后虚拟 replay，不替代 context.messages()。
 */
public record CompactSummaryRequest(
    ContextSnapshot context,
    CompactionPlan plan,
    List<SessionEntry> branchEntries,
    AbortSignal abortSignal
) {
    public CompactSummaryRequest {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(branchEntries, "branchEntries");
        Objects.requireNonNull(abortSignal, "abortSignal");
        branchEntries = List.copyOf(branchEntries);
    }
}
