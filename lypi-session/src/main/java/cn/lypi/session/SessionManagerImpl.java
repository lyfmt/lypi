package cn.lypi.session;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.runtime.SessionStorageRootPort;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.session.BranchSummaryEntry;
import cn.lypi.contracts.session.BranchSummaryPlan;
import cn.lypi.contracts.session.ForkRequest;
import cn.lypi.contracts.session.MessageEntry;
import cn.lypi.contracts.session.SessionContext;
import cn.lypi.contracts.session.SessionEntry;
import cn.lypi.contracts.session.SessionHandle;
import cn.lypi.contracts.session.SessionHeader;
import cn.lypi.contracts.session.SessionView;
import cn.lypi.contracts.tui.SessionFileView;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * SessionManager 的默认实现。
 *
 * NOTE: 该实现编排 JSONL store、entry tree 和 fork 服务，不处理模型或工具执行。
 */
public final class SessionManagerImpl implements SessionManager, SessionStorageRootPort {
    private final Path cwd;
    private final JsonlSessionStore store;
    private final Clock clock;
    private final SessionReplayProjector replayProjector;
    private final SessionFileQuery fileQuery = new SessionFileQuery();
    private String sessionId;
    private SessionHeader header;
    private EntryTreeIndex index;
    private boolean persistent;

    public SessionManagerImpl(Path cwd) {
        this(cwd, Clock.systemUTC());
    }

    SessionManagerImpl(Path cwd, Clock clock) {
        this(cwd, clock, new SessionReplayProjector());
    }

    public SessionManagerImpl(
        Path cwd,
        ModelSelection defaultModel,
        ThinkingLevel defaultThinkingLevel,
        AgentMode defaultMode,
        PermissionMode defaultPermissionMode
    ) {
        this(
            cwd,
            Clock.systemUTC(),
            new SessionReplayProjector(defaultModel, defaultThinkingLevel, defaultMode, defaultPermissionMode)
        );
    }

    private SessionManagerImpl(Path cwd, Clock clock, SessionReplayProjector replayProjector) {
        this.cwd = cwd;
        this.clock = clock;
        this.store = new JsonlSessionStore(cwd);
        this.replayProjector = Objects.requireNonNull(replayProjector, "replayProjector must not be null");
    }

    /**
     * 打开已有 session 或创建新 session。
     */
    @Override
    public SessionHandle openOrCreate(String sessionId) {
        if (this.sessionId != null && this.sessionId.equals(sessionId) && header != null && index != null) {
            return handle();
        }
        this.sessionId = sessionId;
        if (!store.exists(sessionId)) {
            store.create(initialHeader(sessionId));
        }
        SessionFile sessionFile = store.read(sessionId);
        header = sessionFile.header();
        index = new EntryTreeIndex(sessionFile.entries());
        persistent = true;
        return handle();
    }

    /**
     * 打开临时 session。
     */
    @Override
    public SessionHandle openTemporary(String sessionId) {
        this.sessionId = sessionId;
        if (store.exists(sessionId)) {
            SessionFile sessionFile = store.read(sessionId);
            header = sessionFile.header();
            index = new EntryTreeIndex(sessionFile.entries());
            persistent = true;
            return handle();
        }
        header = initialHeader(sessionId);
        index = new EntryTreeIndex(List.of());
        persistent = false;
        return handle();
    }

    /**
     * 追加 entry 并移动当前 leaf。
     */
    @Override
    public SessionHandle append(SessionEntry entry) {
        ensureOpen();
        index.validateAppend(entry);
        if (persistent) {
            store.append(sessionId, entry);
        } else if (shouldPersist(entry)) {
            List<SessionEntry> pendingEntries = new java.util.ArrayList<>(index.entries());
            pendingEntries.add(entry);
            store.createWithEntries(header, pendingEntries);
            persistent = true;
        }
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
     * 查询指定 leaf 的 root-to-leaf 路径。
     */
    @Override
    public List<SessionEntry> branch(String leafId) {
        ensureOpen();
        return index.branch(leafId);
    }

    /**
     * 收集从旧 leaf 离开时需要总结的旧路径后缀。
     */
    @Override
    public BranchSummaryPlan collectBranchSummaryPlan(String oldLeafId, String targetLeafId) {
        ensureOpen();
        if (oldLeafId == null || oldLeafId.isBlank()) {
            return new BranchSummaryPlan(oldLeafId, targetLeafId, Optional.empty(), List.of());
        }
        List<SessionEntry> oldBranch = branch(oldLeafId);
        List<SessionEntry> targetBranch = branch(targetLeafId);
        Set<String> oldIds = oldBranch.stream().map(SessionEntry::id).collect(Collectors.toUnmodifiableSet());
        Optional<String> commonAncestorId = Optional.empty();
        for (int index = targetBranch.size() - 1; index >= 0; index--) {
            String candidate = targetBranch.get(index).id();
            if (oldIds.contains(candidate)) {
                commonAncestorId = Optional.of(candidate);
                break;
            }
        }

        List<SessionEntry> entries = new java.util.ArrayList<>();
        for (int index = oldBranch.size() - 1; index >= 0; index--) {
            SessionEntry entry = oldBranch.get(index);
            if (commonAncestorId.isPresent() && commonAncestorId.orElseThrow().equals(entry.id())) {
                break;
            }
            entries.add(entry);
        }
        java.util.Collections.reverse(entries);
        return new BranchSummaryPlan(oldLeafId, targetLeafId, commonAncestorId, entries);
    }

    List<SessionEntry> leafToRootPath(String leafId) {
        ensureOpen();
        return index.leafToRootPath(leafId);
    }

    @Override
    public SessionView currentView() {
        ensureOpen();
        return view(index.leafId());
    }

    @Override
    public SessionView view(String leafId) {
        ensureOpen();
        return new SessionView(sessionId, leafId);
    }

    @Override
    public List<AgentMessage> transcript(String leafId) {
        ensureOpen();
        return replayProjector.transcript(header, branch(leafId));
    }

    @Override
    public List<SessionFileView> files(String leafId) {
        ensureOpen();
        return fileQuery.files(branch(leafId));
    }

    @Override
    public SessionContext context(String leafId) {
        ensureOpen();
        return replayProjector.context(header, branch(leafId));
    }

    /**
     * 返回该 manager 使用的 session 存储根目录。
     */
    @Override
    public Path sessionStorageRoot() {
        return cwd;
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
     * 追加 branch summary entry。
     */
    @Override
    public SessionHandle appendBranchSummary(String parentId, String fromId, String summary) {
        ensureOpen();
        if (summary == null || summary.isBlank()) {
            throw new SessionEngineException("Branch summary must not be blank");
        }
        String entryId = SessionEntryIds.newEntryId();
        return append(new BranchSummaryEntry(entryId, parentId, fromId, summary, Instant.now(clock)));
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
        return new ForkService(clock).fork(request, header, index);
    }

    /**
     * 删除指定 session 的 JSONL 文件。
     */
    @Override
    public void deleteSession(String sessionId) {
        if (this.sessionId != null && this.sessionId.equals(sessionId) && header != null && index != null) {
            store.delete(sessionId);
            this.sessionId = null;
            header = null;
            index = null;
            persistent = false;
            return;
        }
        store.delete(sessionId);
    }

    private SessionHandle handle() {
        return new SessionHandle(sessionId, store.sessionFile(sessionId), index.leafId(), index.byId());
    }

    private boolean shouldPersist(SessionEntry entry) {
        return entry instanceof MessageEntry messageEntry
            && messageEntry.message() != null
            && messageEntry.message().role() == MessageRole.USER;
    }

    private SessionHeader initialHeader(String sessionId) {
        return new SessionHeader(
            "session",
            1,
            sessionId,
            cwd,
            Optional.empty(),
            Optional.empty(),
            0,
            Optional.empty(),
            Optional.empty(),
            Instant.now(clock),
            Optional.of(replayProjector.defaultModel()),
            Optional.of(replayProjector.defaultThinkingLevel()),
            Optional.of(replayProjector.defaultMode()),
            Optional.of(replayProjector.defaultPermissionMode())
        );
    }

    private void ensureOpen() {
        if (sessionId == null || header == null || index == null) {
            throw new SessionEngineException("Session is not open");
        }
    }
}
