package cn.lypi.agent;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContentBlockKind;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.runtime.SessionManagerPort;
import cn.lypi.contracts.session.MessageEntry;
import cn.lypi.contracts.session.SessionEntry;
import java.util.List;
import java.util.Optional;

final class TurnContinuationGuard {
    private final SessionManagerPort sessionManager;

    TurnContinuationGuard(SessionManagerPort sessionManager) {
        this.sessionManager = sessionManager;
    }

    Optional<String> unsafeContinuationReason(String leafId) {
        if (leafId == null || leafId.isBlank()) {
            return Optional.empty();
        }
        List<SessionEntry> branch = sessionManager.branch(leafId);
        if (branch.isEmpty()) {
            return Optional.empty();
        }
        SessionEntry last = branch.getLast();
        if (!(last instanceof MessageEntry messageEntry) || messageEntry.message() == null) {
            return Optional.empty();
        }
        AgentMessage message = messageEntry.message();
        if (isToolCallAssistant(message)) {
            return Optional.of("cannot-continue-from-tool-call-assistant");
        }
        return Optional.empty();
    }

    private boolean isToolCallAssistant(AgentMessage message) {
        if (message.role() != MessageRole.ASSISTANT || message.content() == null || message.content().isEmpty()) {
            return false;
        }
        if (message.kind() == MessageKind.ERROR) {
            return false;
        }
        return message.content().stream().anyMatch(block -> block.kind() == ContentBlockKind.TOOL_CALL);
    }
}
