package cn.lypi.session;

import cn.lypi.contracts.session.ForkRequest;
import cn.lypi.contracts.session.SessionEntry;
import cn.lypi.contracts.session.SessionHandle;
import cn.lypi.contracts.session.SessionHeader;
import cn.lypi.contracts.session.SessionInfoEntry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 创建 fork session。
 *
 * NOTE: fork 只复制指定 fork point 所在的线性路径，并追加 fork 元信息。
 */
final class ForkService {
    private final Clock clock;

    ForkService(Clock clock) {
        this.clock = clock;
    }

    /**
     * 从源索引派生新的 session。
     */
    SessionHandle fork(ForkRequest request, SessionHeader sourceHeader, EntryTreeIndex sourceIndex) {
        validateRequest(request);
        List<SessionEntry> path = sourceIndex.branch(request.forkPointEntryId());
        String forkSessionId = "ses_" + UUID.randomUUID().toString().replace("-", "");
        SessionHeader header = new SessionHeader(
            "session",
            1,
            forkSessionId,
            request.targetCwd(),
            Optional.of(request.sourceSessionId()),
            Instant.now(clock),
            sourceHeader.initialModel(),
            sourceHeader.initialThinkingLevel(),
            sourceHeader.initialAgentMode(),
            sourceHeader.initialPermissionMode()
        );
        JsonlSessionStore targetStore = new JsonlSessionStore(request.targetCwd());
        targetStore.create(header);
        EntryTreeIndex forkIndex = new EntryTreeIndex(List.of());
        for (SessionEntry entry : path) {
            forkIndex.add(entry);
            targetStore.append(forkSessionId, entry);
        }
        SessionInfoEntry forkInfo = new SessionInfoEntry(
            SessionEntryIds.newEntryId(),
            forkIndex.leafId(),
            Map.of(
                "forkReason",
                request.reason(),
                "sourceSessionId",
                request.sourceSessionId(),
                "forkPointEntryId",
                request.forkPointEntryId()
            ),
            Instant.now(clock)
        );
        forkIndex.add(forkInfo);
        targetStore.append(forkSessionId, forkInfo);
        return new SessionHandle(
            forkSessionId,
            targetStore.sessionFile(forkSessionId),
            forkIndex.leafId(),
            forkIndex.byId()
        );
    }

    private void validateRequest(ForkRequest request) {
        if (request.sourceSessionId() == null || request.sourceSessionId().isBlank()) {
            throw new SessionEngineException("Fork source session id is required");
        }
        if (request.forkPointEntryId() == null || request.forkPointEntryId().isBlank()) {
            throw new SessionEngineException("Fork point entry id is required");
        }
        if (request.targetCwd() == null) {
            throw new SessionEngineException("Fork target cwd is required");
        }
        if (request.reason() == null || request.reason().isBlank()) {
            throw new SessionEngineException("Fork reason is required");
        }
    }
}
