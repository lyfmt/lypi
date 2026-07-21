package cn.lypi.contracts.runtime;

import cn.lypi.contracts.subagent.AgentRunStatus;
import cn.lypi.contracts.subagent.AgentView;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface AgentRegistryPort {
    /**
     * 查询 parent session 下的 subagent 视图。
     *
     * 空 status 集合表示不过滤。
     */
    List<AgentView> list(String parentSessionId, Set<AgentRunStatus> statuses);

    /**
     * 查询 parent session 指定 leaf 下的 subagent 视图。
     *
     * NOTE: 未提供 leaf 时兼容旧行为，由实现决定是否使用当前 view。
     */
    default List<AgentView> list(
        String parentSessionId,
        Optional<String> leafEntryId,
        Set<AgentRunStatus> statuses
    ) {
        return list(parentSessionId, statuses);
    }
}
