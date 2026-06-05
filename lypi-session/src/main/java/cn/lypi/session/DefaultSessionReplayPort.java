package cn.lypi.session;

import cn.lypi.contracts.memory.MemoryWriteEntry;
import cn.lypi.contracts.session.FileChangeEntry;
import cn.lypi.contracts.session.MessageEntry;
import cn.lypi.contracts.session.PermissionDecisionEntry;
import cn.lypi.contracts.session.SessionEntry;
import cn.lypi.contracts.session.SessionHandle;
import cn.lypi.contracts.session.SessionReplayPort;
import cn.lypi.contracts.session.SessionView;
import cn.lypi.contracts.session.ToolUseAuditEntry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class DefaultSessionReplayPort implements SessionReplayPort {
    private final SessionEngine engine;

    public DefaultSessionReplayPort(SessionEngine engine) {
        this.engine = engine;
    }

    /**
     * 返回当前 leaf 的可恢复 session 视图。
     */
    @Override
    public SessionView currentView() {
        SessionHandle current = engine.current();
        return view(current.leafId());
    }

    /**
     * 返回指定 leaf 的可恢复 session 视图。
     */
    @Override
    public SessionView view(String leafId) {
        List<SessionEntry> leafToRoot = engine.pathToRoot(leafId);
        List<SessionEntry> rootToLeaf = new ArrayList<>(leafToRoot);
        Collections.reverse(rootToLeaf);

        List<MessageEntry> transcript = new ArrayList<>();
        List<ToolUseAuditEntry> tools = new ArrayList<>();
        List<FileChangeEntry> fileChanges = new ArrayList<>();
        List<PermissionDecisionEntry> permissionDecisions = new ArrayList<>();
        List<MemoryWriteEntry> memoryWrites = new ArrayList<>();

        for (SessionEntry entry : rootToLeaf) {
            if (entry instanceof MessageEntry messageEntry) {
                transcript.add(messageEntry);
            } else if (entry instanceof ToolUseAuditEntry toolEntry) {
                tools.add(toolEntry);
            } else if (entry instanceof FileChangeEntry fileChangeEntry) {
                fileChanges.add(fileChangeEntry);
            } else if (entry instanceof PermissionDecisionEntry permissionDecisionEntry) {
                permissionDecisions.add(permissionDecisionEntry);
            } else if (entry instanceof MemoryWriteEntry memoryWriteEntry) {
                memoryWrites.add(memoryWriteEntry);
            }
        }

        Collections.reverse(tools);
        Collections.reverse(fileChanges);
        Collections.reverse(permissionDecisions);
        Collections.reverse(memoryWrites);

        SessionHandle current = engine.current();
        return new SessionView(
            current.sessionId(),
            leafId,
            transcript,
            tools,
            fileChanges,
            permissionDecisions,
            memoryWrites
        );
    }
}
