package cn.lypi.runtime.subagent;

import java.util.List;

@FunctionalInterface
public interface RunningAgentSnapshotProvider {
    /**
     * 返回指定 parent session 当前进程内仍在运行的 agent。
     */
    List<RunningAgentSnapshot> runningAgents(String parentSessionId);
}
