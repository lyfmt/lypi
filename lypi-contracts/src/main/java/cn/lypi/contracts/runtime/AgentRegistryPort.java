package cn.lypi.contracts.runtime;

import cn.lypi.contracts.subagent.AgentRunStatus;
import cn.lypi.contracts.subagent.AgentView;
import java.util.List;
import java.util.Set;

public interface AgentRegistryPort {
    /**
     * 查询 parent session 下的 subagent 视图。
     *
     * 空 status 集合表示不过滤。
     */
    List<AgentView> list(String parentSessionId, Set<AgentRunStatus> statuses);
}
