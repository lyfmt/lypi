package cn.lypi.session;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.session.ForkRequest;
import cn.lypi.contracts.session.MessageEntry;
import cn.lypi.contracts.session.SessionEntry;
import cn.lypi.contracts.session.SessionHandle;
import cn.lypi.contracts.session.SessionHeader;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public final class SessionEngineImpl implements SessionEngine {
    private final Path cwd;
    private final JsonlSessionStore store;
    private final Clock clock;
    private String sessionId;
    private EntryTreeIndex index;

    public SessionEngineImpl(Path cwd) {
        this(cwd, Clock.systemUTC());
    }

    SessionEngineImpl(Path cwd, Clock clock) {
        this.cwd = cwd;
        this.clock = clock;
        this.store = new JsonlSessionStore(cwd);
    }

    @Override
    public SessionHandle openOrCreate(String sessionId) {
        this.sessionId = sessionId;
        if (!store.exists(sessionId)) {
            store.create(new SessionHeader("session", 1, sessionId, cwd, Optional.empty(), Instant.now(clock)));
        }
        SessionFile sessionFile = store.read(sessionId);
        index = new EntryTreeIndex(sessionFile.entries());
        return handle();
    }

    @Override
    public SessionHandle append(SessionEntry entry) {
        ensureOpen();
        index.validateAppend(entry);
        store.append(sessionId, entry);
        index.add(entry);
        return handle();
    }

    @Override
    public SessionHandle switchLeaf(String leafId) {
        ensureOpen();
        index.switchLeaf(leafId);
        return handle();
    }

    @Override
    public List<SessionEntry> pathToRoot(String leafId) {
        ensureOpen();
        return index.pathToRoot(leafId);
    }

    @Override
    public SessionHandle appendMessage(AgentMessage message) {
        ensureOpen();
        String entryId = SessionEntryIds.newEntryId();
        MessageEntry entry = new MessageEntry(entryId, index.leafId(), message, message.timestamp());
        return append(entry);
    }

    @Override
    public SessionHandle fork(ForkRequest request) {
        ensureOpen();
        if (request.sourceSessionId() == null || request.sourceSessionId().isBlank()) {
            throw new SessionEngineException("Fork source session id is required");
        }
        if (!sessionId.equals(request.sourceSessionId())) {
            throw new SessionEngineException("Fork source session does not match open session: " + request.sourceSessionId());
        }
        return new ForkService(clock).fork(request, index);
    }

    private SessionHandle handle() {
        return new SessionHandle(sessionId, store.sessionFile(sessionId), index.leafId(), index.byId());
    }

    private void ensureOpen() {
        if (sessionId == null || index == null) {
            throw new SessionEngineException("Session is not open");
        }
    }
}
