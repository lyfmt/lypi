package cn.lypi.contracts.session;

import cn.lypi.contracts.memory.MemoryWriteEntry;
import java.util.List;

public record SessionView(
    String sessionId,
    String leafId,
    List<MessageEntry> transcript,
    List<ToolUseAuditEntry> recentTools,
    List<FileChangeEntry> recentFileChanges,
    List<PermissionDecisionEntry> recentPermissionDecisions,
    List<MemoryWriteEntry> memoryWrites
) {
    public SessionView {
        transcript = List.copyOf(transcript);
        recentTools = List.copyOf(recentTools);
        recentFileChanges = List.copyOf(recentFileChanges);
        recentPermissionDecisions = List.copyOf(recentPermissionDecisions);
        memoryWrites = List.copyOf(memoryWrites);
    }
}
