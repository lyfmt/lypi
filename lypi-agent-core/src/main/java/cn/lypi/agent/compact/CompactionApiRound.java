package cn.lypi.agent.compact;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.session.SessionEntry;
import java.util.List;

record CompactionApiRound(
    List<SessionEntry> entries,
    List<AgentMessage> messages
) {
    CompactionApiRound {
        entries = List.copyOf(entries == null ? List.of() : entries);
        messages = List.copyOf(messages == null ? List.of() : messages);
    }
}
