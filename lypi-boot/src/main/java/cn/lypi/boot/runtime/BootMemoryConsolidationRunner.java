package cn.lypi.boot.runtime;

import cn.lypi.contracts.agent.TurnRequest;
import cn.lypi.contracts.runtime.AgentCoreFactoryPort;
import cn.lypi.contracts.runtime.SessionManagerPort;
import cn.lypi.contracts.session.ForkRequest;
import cn.lypi.contracts.session.SessionHandle;
import cn.lypi.runtime.memory.MemoryConsolidationAuditRecord;
import cn.lypi.runtime.memory.MemoryConsolidationAuditSink;
import cn.lypi.runtime.memory.MemoryConsolidationAuditStage;
import cn.lypi.runtime.memory.MemoryLintDiagnostic;
import cn.lypi.runtime.memory.MemoryLintScanner;
import cn.lypi.runtime.memory.MemoryPreflightScan;
import cn.lypi.runtime.memory.MemoryConsolidationPromptFactory;
import cn.lypi.runtime.memory.MemoryConsolidationRequest;
import cn.lypi.runtime.memory.MemoryConsolidationRunner;
import cn.lypi.session.SessionManagerImpl;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Boot 层后台记忆沉淀执行器。
 */
public final class BootMemoryConsolidationRunner implements MemoryConsolidationRunner {
    static final String FORK_REASON = "memory_consolidation";

    private final Path cwd;
    private final SessionManagerPort mainSessionManager;
    private final AgentCoreFactoryPort agentCoreFactory;
    private final MemoryConsolidationPromptFactory promptFactory;
    private final MemoryConsolidationAuditSink auditSink;
    private final Function<Path, SessionManagerPort> forkSessionManagerFactory;

    public BootMemoryConsolidationRunner(
        Path cwd,
        SessionManagerPort mainSessionManager,
        AgentCoreFactoryPort agentCoreFactory,
        MemoryConsolidationPromptFactory promptFactory
    ) {
        this(cwd, mainSessionManager, agentCoreFactory, promptFactory, MemoryConsolidationAuditSink.noop());
    }

    public BootMemoryConsolidationRunner(
        Path cwd,
        SessionManagerPort mainSessionManager,
        AgentCoreFactoryPort agentCoreFactory,
        MemoryConsolidationPromptFactory promptFactory,
        MemoryConsolidationAuditSink auditSink
    ) {
        this(cwd, mainSessionManager, agentCoreFactory, promptFactory, auditSink, SessionManagerImpl::new);
    }

    BootMemoryConsolidationRunner(
        Path cwd,
        SessionManagerPort mainSessionManager,
        AgentCoreFactoryPort agentCoreFactory,
        MemoryConsolidationPromptFactory promptFactory,
        MemoryConsolidationAuditSink auditSink,
        Function<Path, SessionManagerPort> forkSessionManagerFactory
    ) {
        this.cwd = Objects.requireNonNull(cwd, "cwd must not be null").toAbsolutePath().normalize();
        this.mainSessionManager = Objects.requireNonNull(mainSessionManager, "mainSessionManager must not be null");
        this.agentCoreFactory = Objects.requireNonNull(agentCoreFactory, "agentCoreFactory must not be null");
        this.promptFactory = Objects.requireNonNull(promptFactory, "promptFactory must not be null");
        this.auditSink = Objects.requireNonNull(auditSink, "auditSink must not be null");
        this.forkSessionManagerFactory = Objects.requireNonNull(forkSessionManagerFactory, "forkSessionManagerFactory must not be null");
    }

    /**
     * fork 当前分支、执行隐藏沉淀 turn，并清理临时 session。
     */
    @Override
    public void run(MemoryConsolidationRequest request) {
        if (request == null || request.forkPointEntryId() == null || request.forkPointEntryId().isBlank()) {
            return;
        }
        audit(MemoryConsolidationAuditStage.RUN_STARTED, request, null, "started", null);
        SessionHandle forked = null;
        SessionManagerPort forkSessionManager = null;
        Instant lintBaseline = Instant.now();
        MemoryPreflightScan preflightScan = preflightScan(request);
        try {
            forked = mainSessionManager.fork(new ForkRequest(
                request.sessionId(),
                request.forkPointEntryId(),
                cwd,
                FORK_REASON
            ));
            audit(MemoryConsolidationAuditStage.FORK_CREATED, request, forked.sessionId(), "forked", null);
            forkSessionManager = forkSessionManagerFactory.apply(cwd);
            forkSessionManager.openOrCreate(forked.sessionId());
            var turnState = agentCoreFactory.create(cwd, forkSessionManager)
                .execute(new TurnRequest(
                    forked.sessionId(),
                    promptFactory.prompt(preflightScan),
                    Optional.of(request.forkPointEntryId()),
                    () -> false,
                    TurnRequest.DEFAULT_MAX_TOOL_ROUNDS
                ));
            audit(
                MemoryConsolidationAuditStage.TURN_COMPLETED,
                request,
                forked.sessionId(),
                "background turn " + turnState.status(),
                null
            );
            runLint(request, forked.sessionId(), lintBaseline);
        } catch (RuntimeException exception) {
            audit(
                MemoryConsolidationAuditStage.RUN_FAILED,
                request,
                forked == null ? null : forked.sessionId(),
                "run failed",
                exception
            );
            throw exception;
        } finally {
            if (forked != null && forkSessionManager != null) {
                try {
                    forkSessionManager.deleteSession(forked.sessionId());
                    audit(MemoryConsolidationAuditStage.CLEANED, request, forked.sessionId(), "cleaned", null);
                } catch (RuntimeException exception) {
                    audit(MemoryConsolidationAuditStage.RUN_FAILED, request, forked.sessionId(), "cleanup failed", exception);
                    throw exception;
                }
            }
        }
    }

    private MemoryPreflightScan preflightScan(MemoryConsolidationRequest request) {
        try {
            MemoryPreflightScan scan = new MemoryLintScanner(cwd).scanAll();
            return new MemoryPreflightScan(
                relativizeAll(scan.manifestPaths()),
                relativizeAll(scan.memoryPaths()),
                scan.diagnostics().stream()
                    .map(diagnostic -> new MemoryLintDiagnostic(
                        diagnostic.code(),
                        cwd.relativize(diagnostic.path()).normalize(),
                        diagnostic.message()
                    ))
                    .toList()
            );
        } catch (IOException | RuntimeException exception) {
            audit(MemoryConsolidationAuditStage.LINT_FAILED, request, null, "preflight lint failed", exception);
            return null;
        }
    }

    private void runLint(MemoryConsolidationRequest request, String forkSessionId, Instant baseline) {
        try {
            List<Path> changedPaths = changedMemoryPathsSince(baseline);
            List<MemoryLintDiagnostic> diagnostics = new MemoryLintScanner(cwd).scan(changedPaths);
            auditSink.record(new MemoryConsolidationAuditRecord(
                MemoryConsolidationAuditStage.LINT_COMPLETED,
                request.sessionId(),
                null,
                request.forkPointEntryId(),
                forkSessionId,
                0L,
                0,
                "lint diagnostics: " + diagnostics.size(),
                null,
                Instant.now(),
                changedPaths.stream().map(path -> cwd.relativize(path).toString()).toList(),
                diagnostics.stream().map(MemoryLintDiagnostic::code).toList(),
                false
            ));
        } catch (IOException | RuntimeException exception) {
            audit(MemoryConsolidationAuditStage.LINT_FAILED, request, forkSessionId, "lint failed", exception);
        }
    }

    private List<Path> relativizeAll(List<Path> paths) {
        return paths.stream()
            .map(path -> cwd.relativize(path).normalize())
            .toList();
    }

    private List<Path> changedMemoryPathsSince(Instant baseline) throws IOException {
        List<Path> roots = List.of(
            cwd.resolve("MEMORY.md"),
            cwd.resolve(".ly-pi/memory.md"),
            cwd.resolve(".ly-pi/memory"),
            cwd.resolve(".ly-pi/skills")
        );
        List<Path> paths = new ArrayList<>();
        for (Path root : roots) {
            if (!Files.exists(root)) {
                continue;
            }
            if (Files.isRegularFile(root)) {
                addIfChanged(root, baseline, paths);
                continue;
            }
            try (var stream = Files.walk(root)) {
                for (Path path : stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName() != null && path.getFileName().toString().endsWith(".md"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList()) {
                    addIfChanged(path, baseline, paths);
                }
            }
        }
        return paths;
    }

    private void addIfChanged(Path path, Instant baseline, List<Path> paths) throws IOException {
        if (!Files.getLastModifiedTime(path).toInstant().isBefore(baseline)) {
            paths.add(path.toAbsolutePath().normalize());
        }
    }

    private void audit(
        MemoryConsolidationAuditStage stage,
        MemoryConsolidationRequest request,
        String forkSessionId,
        String reason,
        Throwable error
    ) {
        auditSink.record(new MemoryConsolidationAuditRecord(
            stage,
            request.sessionId(),
            null,
            request.forkPointEntryId(),
            forkSessionId,
            0L,
            0,
            reason,
            errorSummary(error),
            Instant.now()
        ));
    }

    private static String errorSummary(Throwable error) {
        if (error == null) {
            return null;
        }
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }
}
