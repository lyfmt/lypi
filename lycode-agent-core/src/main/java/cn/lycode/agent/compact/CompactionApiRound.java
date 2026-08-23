package cn.lycode.agent.compact;

import cn.lycode.contracts.context.AgentMessage;
import cn.lycode.contracts.session.SessionEntry;
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
