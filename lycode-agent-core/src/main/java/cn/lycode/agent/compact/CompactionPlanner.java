package cn.lycode.agent.compact;

import cn.lycode.contracts.context.ContextSnapshot;
import cn.lycode.contracts.session.CompactionPlan;
import cn.lycode.contracts.session.SessionEntry;
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
