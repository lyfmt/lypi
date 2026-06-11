package cn.lypi.session;

import cn.lypi.contracts.session.AgentLifecycleEntry;
import cn.lypi.contracts.session.CustomEntry;
import cn.lypi.contracts.session.LabelEntry;
import cn.lypi.contracts.session.ModeChangeEntry;
import cn.lypi.contracts.session.ModelChangeEntry;
import cn.lypi.contracts.session.PermissionModeChangeEntry;
import cn.lypi.contracts.session.SessionEntry;
import cn.lypi.contracts.session.SessionInfoEntry;
import cn.lypi.contracts.session.ThinkingChangeEntry;
import java.util.List;

final class SessionLeafSelector {
    private SessionLeafSelector() {
    }

    static String latestNavigableLeaf(List<SessionEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return null;
        }
        for (int index = entries.size() - 1; index >= 0; index--) {
            SessionEntry entry = entries.get(index);
            if (advancesNavigableLeaf(entry)) {
                return entry.id();
            }
        }
        return null;
    }

    private static boolean advancesNavigableLeaf(SessionEntry entry) {
        return !(entry instanceof AgentLifecycleEntry)
            && !(entry instanceof ModelChangeEntry)
            && !(entry instanceof ThinkingChangeEntry)
            && !(entry instanceof ModeChangeEntry)
            && !(entry instanceof PermissionModeChangeEntry)
            && !(entry instanceof SessionInfoEntry)
            && !(entry instanceof LabelEntry)
            && !(entry instanceof CustomEntry);
    }
}
