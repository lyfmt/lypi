package cn.lypi.agent.compact;

import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.session.CompactionPlan;
import cn.lypi.contracts.session.SessionEntry;
import java.util.List;
import java.util.Optional;

public interface CompactionPlanner {
    /**
     * 规划一次 session 压缩切点。
     *
     * 返回空表示当前上下文无需压缩，或没有安全切点。
     */
    Optional<CompactionPlan> plan(List<SessionEntry> branchEntries, ContextSnapshot context);
}
