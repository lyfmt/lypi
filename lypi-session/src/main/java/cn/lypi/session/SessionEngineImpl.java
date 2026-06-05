package cn.lypi.session;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.audit.AuditKind;
import cn.lypi.contracts.audit.AuditRecord;
import cn.lypi.contracts.common.IdGenerator;
import cn.lypi.contracts.memory.MemoryWriteEntry;
import cn.lypi.contracts.session.FileChangeEntry;
import cn.lypi.contracts.session.ForkRequest;
import cn.lypi.contracts.session.MessageEntry;
import cn.lypi.contracts.session.PermissionDecisionEntry;
import cn.lypi.contracts.session.SessionEntry;
import cn.lypi.contracts.session.SessionHandle;
import cn.lypi.contracts.session.SessionHeader;
import cn.lypi.contracts.session.ToolUseAuditEntry;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * SessionEngine 的默认实现。
 *
 * NOTE: 该实现编排 JSONL store、entry tree 和 fork 服务，不处理模型或工具执行。
 */
public final class SessionEngineImpl implements SessionEngine {
    private final Path cwd;
    private final JsonlSessionStore store;
    private final JsonlAuditStore auditStore;
    private final IdGenerator idGenerator;
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
        this.auditStore = new JsonlAuditStore(cwd);
        this.idGenerator = IdGenerator.random();
    }

    /**
     * 打开已有 session 或创建新 session。
     */
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

    /**
     * 返回当前 session 句柄。
     */
    @Override
    public SessionHandle current() {
        ensureOpen();
        return handle();
    }

    /**
     * 追加 entry 并移动当前 leaf。
     */
    @Override
    public SessionHandle append(SessionEntry entry) {
        ensureOpen();
        index.validateAppend(entry);
        store.append(sessionId, entry);
        appendAuditRecord(entry);
        index.add(entry);
        return handle();
    }

    /**
     * 切换当前 leaf。
     */
    @Override
    public SessionHandle switchLeaf(String leafId) {
        ensureOpen();
        index.switchLeaf(leafId);
        return handle();
    }

    /**
     * 查询指定 leaf 到 root 的路径。
     */
    @Override
    public List<SessionEntry> pathToRoot(String leafId) {
        ensureOpen();
        return index.pathToRoot(leafId);
    }

    /**
     * 追加消息 entry。
     */
    @Override
    public SessionHandle appendMessage(AgentMessage message) {
        ensureOpen();
        String entryId = SessionEntryIds.newEntryId();
        MessageEntry entry = new MessageEntry(entryId, index.leafId(), message, message.timestamp());
        return append(entry);
    }

    /**
     * 基于当前 session 创建 fork session。
     */
    @Override
    public SessionHandle fork(ForkRequest request) {
        ensureOpen();
        if (request == null) {
            throw new SessionEngineException("Fork request is required");
        }
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

    private void appendAuditRecord(SessionEntry entry) {
        AuditRecord record = auditRecord(entry);
        if (record != null) {
            auditStore.append(record);
        }
    }

    private AuditRecord auditRecord(SessionEntry entry) {
        if (entry instanceof ToolUseAuditEntry toolEntry) {
            return new AuditRecord(
                idGenerator.auditId(),
                sessionId,
                entry.id(),
                AuditKind.TOOL_USE,
                Optional.ofNullable(toolEntry.toolUseId()),
                Optional.ofNullable(toolEntry.parentMessageId()),
                details(
                    "toolName", toolEntry.toolName(),
                    "status", toolEntry.status() == null ? null : toolEntry.status().name(),
                    "durationMillis", toolEntry.durationMillis()
                )
            );
        }
        if (entry instanceof PermissionDecisionEntry permissionEntry) {
            return new AuditRecord(
                idGenerator.auditId(),
                sessionId,
                entry.id(),
                AuditKind.PERMISSION_DECISION,
                Optional.ofNullable(permissionEntry.toolUseId()),
                Optional.empty(),
                details(
                    "toolName", permissionEntry.toolName(),
                    "behavior", permissionEntry.decision() == null ? null : permissionEntry.decision().behavior().name()
                )
            );
        }
        if (entry instanceof FileChangeEntry fileEntry) {
            return new AuditRecord(
                idGenerator.auditId(),
                sessionId,
                entry.id(),
                AuditKind.FILE_CHANGE,
                Optional.ofNullable(fileEntry.toolUseId()),
                Optional.ofNullable(fileEntry.messageId()),
                details(
                    "path", fileEntry.path() == null ? null : fileEntry.path().toString(),
                    "operation", fileEntry.operation() == null ? null : fileEntry.operation().name()
                )
            );
        }
        if (entry instanceof MemoryWriteEntry memoryEntry) {
            return new AuditRecord(
                idGenerator.auditId(),
                sessionId,
                entry.id(),
                AuditKind.MEMORY_WRITE,
                Optional.empty(),
                Optional.ofNullable(memoryEntry.sourceMessageId()),
                details(
                    "scope", memoryEntry.scope() == null ? null : memoryEntry.scope().name(),
                    "targetPath", memoryEntry.targetPath() == null ? null : memoryEntry.targetPath().toString(),
                    "contentHash", memoryEntry.contentHash()
                )
            );
        }
        return null;
    }

    private Map<String, Object> details(Object... pairs) {
        Map<String, Object> details = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            String key = (String) pairs[i];
            Object value = pairs[i + 1];
            if (value != null) {
                details.put(key, value);
            }
        }
        return Map.copyOf(details);
    }

    private void ensureOpen() {
        if (sessionId == null || index == null) {
            throw new SessionEngineException("Session is not open");
        }
    }
}
