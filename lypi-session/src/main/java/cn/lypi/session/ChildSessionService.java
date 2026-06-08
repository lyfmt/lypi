package cn.lypi.session;

import cn.lypi.contracts.runtime.ChildSessionPort;
import cn.lypi.contracts.session.ChildSessionRequest;
import cn.lypi.contracts.session.SessionHandle;
import cn.lypi.contracts.session.SessionHeader;
import cn.lypi.contracts.session.SessionInfoEntry;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 创建 subagent child session。
 *
 * NOTE: child session 不复制父分支，只通过 header 和初始 session info 记录父子关系。
 */
public final class ChildSessionService implements ChildSessionPort {
    private final Clock clock;

    public ChildSessionService() {
        this(Clock.systemUTC());
    }

    ChildSessionService(Clock clock) {
        this.clock = clock;
    }

    /**
     * 创建独立 child session。
     */
    @Override
    public SessionHandle create(ChildSessionRequest request) {
        validate(request);
        JsonlSessionStore store = new JsonlSessionStore(request.cwd());
        Instant now = Instant.now(clock);
        int depth = depth(request, store);
        SessionHeader header = new SessionHeader(
            "session",
            1,
            request.childSessionId(),
            request.cwd(),
            java.util.Optional.of(request.parentSessionId()),
            java.util.Optional.of(request.parentSpawnEntryId()),
            depth,
            request.agentName(),
            request.agentRole(),
            now
        );
        store.create(header);

        EntryTreeIndex index = new EntryTreeIndex(List.of());
        SessionInfoEntry info = new SessionInfoEntry(
            SessionEntryIds.newEntryId(),
            null,
            metadata(request, depth),
            now
        );
        index.add(info);
        store.append(request.childSessionId(), info);
        return new SessionHandle(
            request.childSessionId(),
            store.sessionFile(request.childSessionId()),
            index.leafId(),
            index.byId()
        );
    }

    private int depth(ChildSessionRequest request, JsonlSessionStore store) {
        if (!store.exists(request.parentSessionId())) {
            return request.depth();
        }
        return store.read(request.parentSessionId()).header().depth() + 1;
    }

    private Map<String, Object> metadata(ChildSessionRequest request, int depth) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("kind", "subagent_child_session");
        metadata.put("parentSessionId", request.parentSessionId());
        metadata.put("parentSpawnEntryId", request.parentSpawnEntryId());
        metadata.put("depth", depth);
        request.agentName().ifPresent(value -> metadata.put("agentName", value));
        request.agentRole().ifPresent(value -> metadata.put("agentRole", value));
        return Map.copyOf(metadata);
    }

    private void validate(ChildSessionRequest request) {
        if (request == null) {
            throw new SessionEngineException("Child session request is required");
        }
        if (blank(request.childSessionId())) {
            throw new SessionEngineException("Child session id is required");
        }
        if (blank(request.parentSessionId())) {
            throw new SessionEngineException("Parent session id is required");
        }
        if (blank(request.parentSpawnEntryId())) {
            throw new SessionEngineException("Parent spawn entry id is required");
        }
        if (request.cwd() == null) {
            throw new SessionEngineException("Child session cwd is required");
        }
        if (request.depth() < 0) {
            throw new SessionEngineException("Child session depth must not be negative");
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
