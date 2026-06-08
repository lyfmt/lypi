package cn.lypi.agent.compact;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.session.MessageEntry;
import cn.lypi.contracts.session.SessionEntry;
import java.util.ArrayList;
import java.util.List;

final class CompactionApiRoundGrouper {
    private CompactionApiRoundGrouper() {
    }

    static List<CompactionApiRound> groupEntries(List<SessionEntry> entries) {
        List<CompactionApiRound> groups = new ArrayList<>();
        List<SessionEntry> currentEntries = new ArrayList<>();
        List<AgentMessage> currentMessages = new ArrayList<>();
        String lastAssistantId = null;

        for (SessionEntry entry : entries) {
            AgentMessage message = message(entry);
            if (startsNewAssistantRound(message, lastAssistantId, !currentEntries.isEmpty())) {
                groups.add(new CompactionApiRound(currentEntries, currentMessages));
                currentEntries = new ArrayList<>();
                currentMessages = new ArrayList<>();
            }

            currentEntries.add(entry);
            if (message != null) {
                currentMessages.add(message);
                if (message.role() == MessageRole.ASSISTANT) {
                    lastAssistantId = message.id();
                }
            }
        }

        if (!currentEntries.isEmpty()) {
            groups.add(new CompactionApiRound(currentEntries, currentMessages));
        }
        return List.copyOf(groups);
    }

    static List<CompactionApiRound> groupMessages(List<AgentMessage> messages) {
        List<CompactionApiRound> groups = new ArrayList<>();
        List<AgentMessage> current = new ArrayList<>();
        String lastAssistantId = null;

        for (AgentMessage message : messages) {
            if (startsNewAssistantRound(message, lastAssistantId, !current.isEmpty())) {
                groups.add(new CompactionApiRound(List.of(), current));
                current = new ArrayList<>();
            }

            current.add(message);
            if (message.role() == MessageRole.ASSISTANT) {
                lastAssistantId = message.id();
            }
        }

        if (!current.isEmpty()) {
            groups.add(new CompactionApiRound(List.of(), current));
        }
        return List.copyOf(groups);
    }

    private static boolean startsNewAssistantRound(AgentMessage message, String lastAssistantId, boolean currentNotEmpty) {
        return message != null
            && message.role() == MessageRole.ASSISTANT
            && !message.id().equals(lastAssistantId)
            && currentNotEmpty;
    }

    private static AgentMessage message(SessionEntry entry) {
        if (entry instanceof MessageEntry messageEntry) {
            return messageEntry.message();
        }
        return null;
    }
}
