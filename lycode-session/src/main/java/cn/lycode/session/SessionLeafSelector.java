package cn.lycode.session;

import cn.lycode.contracts.context.AgentMessage;
import cn.lycode.contracts.context.ContentBlockKind;
import cn.lycode.contracts.context.MessageRole;
import cn.lycode.contracts.session.CustomEntry;
import cn.lycode.contracts.session.LabelEntry;
import cn.lycode.contracts.session.MessageEntry;
import cn.lycode.contracts.session.ModeChangeEntry;
import cn.lycode.contracts.session.ModelChangeEntry;
import cn.lycode.contracts.session.PermissionAmendmentEntry;
import cn.lycode.contracts.session.PermissionModeChangeEntry;
import cn.lycode.contracts.session.PermissionRuntimeStateChangeEntry;
import cn.lycode.contracts.session.SessionEntry;
import cn.lycode.contracts.session.SessionInfoEntry;
import cn.lycode.contracts.session.ShellStateChangeEntry;
import cn.lycode.contracts.session.ThinkingChangeEntry;
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

    static boolean advancesNavigableLeaf(SessionEntry entry) {
        return !isToolCallAssistant(entry)
            && !isToolResult(entry)
            && !(entry instanceof ModelChangeEntry)
            && !(entry instanceof ThinkingChangeEntry)
            && !(entry instanceof ModeChangeEntry)
            && !(entry instanceof PermissionModeChangeEntry)
            && !(entry instanceof PermissionRuntimeStateChangeEntry)
            && !(entry instanceof PermissionAmendmentEntry)
            && !(entry instanceof ShellStateChangeEntry)
            && !(entry instanceof SessionInfoEntry)
            && !(entry instanceof LabelEntry)
            && !(entry instanceof CustomEntry);
    }

    private static boolean isToolCallAssistant(SessionEntry entry) {
        if (!(entry instanceof MessageEntry messageEntry) || messageEntry.message() == null) {
            return false;
        }
        AgentMessage message = messageEntry.message();
        if (message.role() != MessageRole.ASSISTANT || message.content() == null || message.content().isEmpty()) {
            return false;
        }
        return message.content().stream().anyMatch(block -> block.kind() == ContentBlockKind.TOOL_CALL);
    }

    private static boolean isToolResult(SessionEntry entry) {
        return entry instanceof MessageEntry messageEntry
            && messageEntry.message() != null
            && messageEntry.message().role() == MessageRole.TOOL_RESULT;
    }
}
