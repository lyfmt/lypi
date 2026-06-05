package cn.lypi.contracts.transport;

import cn.lypi.contracts.audit.AuditQueryPort;
import cn.lypi.contracts.event.EventBus;
import cn.lypi.contracts.runtime.StateStream;
import cn.lypi.contracts.session.SessionReplayPort;
import cn.lypi.contracts.tui.SessionRuntimeState;
import java.util.Optional;

public record TransportRuntimeView(
    EventBus events,
    StateStream<SessionRuntimeState> sessionState,
    SessionReplayPort sessionReplay,
    AuditQueryPort auditQuery,
    Optional<String> pendingTurnId,
    Optional<String> pendingApprovalToolUseId
) {
    public TransportRuntimeView {
        pendingTurnId = pendingTurnId == null ? Optional.empty() : pendingTurnId;
        pendingApprovalToolUseId = pendingApprovalToolUseId == null ? Optional.empty() : pendingApprovalToolUseId;
    }
}
