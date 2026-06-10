package cn.lypi.runtime.subagent;

import java.util.List;

@FunctionalInterface
public interface ChildAgentSnapshotProvider {
    /**
     * 返回指定 parent session 已持久化的 child agent sessions。
     */
    List<ChildAgentSnapshot> childAgents(String parentSessionId);
}
