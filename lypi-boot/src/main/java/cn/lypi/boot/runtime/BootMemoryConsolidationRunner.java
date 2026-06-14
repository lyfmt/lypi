package cn.lypi.boot.runtime;

import cn.lypi.contracts.agent.TurnRequest;
import cn.lypi.contracts.runtime.AgentCoreFactoryPort;
import cn.lypi.contracts.runtime.SessionManagerPort;
import cn.lypi.contracts.session.ForkRequest;
import cn.lypi.contracts.session.SessionHandle;
import cn.lypi.runtime.memory.MemoryConsolidationPromptFactory;
import cn.lypi.runtime.memory.MemoryConsolidationRequest;
import cn.lypi.runtime.memory.MemoryConsolidationRunner;
import cn.lypi.session.SessionManagerImpl;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Boot 层后台记忆沉淀执行器。
 */
public final class BootMemoryConsolidationRunner implements MemoryConsolidationRunner {
    static final String FORK_REASON = "memory_consolidation";

    private final Path cwd;
    private final SessionManagerPort mainSessionManager;
    private final AgentCoreFactoryPort agentCoreFactory;
    private final MemoryConsolidationPromptFactory promptFactory;

    public BootMemoryConsolidationRunner(
        Path cwd,
        SessionManagerPort mainSessionManager,
        AgentCoreFactoryPort agentCoreFactory,
        MemoryConsolidationPromptFactory promptFactory
    ) {
        this.cwd = Objects.requireNonNull(cwd, "cwd must not be null").toAbsolutePath().normalize();
        this.mainSessionManager = Objects.requireNonNull(mainSessionManager, "mainSessionManager must not be null");
        this.agentCoreFactory = Objects.requireNonNull(agentCoreFactory, "agentCoreFactory must not be null");
        this.promptFactory = Objects.requireNonNull(promptFactory, "promptFactory must not be null");
    }

    /**
     * fork 当前分支、执行隐藏沉淀 turn，并清理临时 session。
     */
    @Override
    public void run(MemoryConsolidationRequest request) {
        if (request == null || request.forkPointEntryId() == null || request.forkPointEntryId().isBlank()) {
            return;
        }
        SessionHandle forked = mainSessionManager.fork(new ForkRequest(
            request.sessionId(),
            request.forkPointEntryId(),
            cwd,
            FORK_REASON
        ));
        SessionManagerPort forkSessionManager = new SessionManagerImpl(cwd);
        forkSessionManager.openOrCreate(forked.sessionId());
        try {
            agentCoreFactory.create(cwd, forkSessionManager)
                .execute(new TurnRequest(
                    forked.sessionId(),
                    promptFactory.prompt(),
                    Optional.of(request.forkPointEntryId()),
                    () -> false,
                    TurnRequest.DEFAULT_MAX_TOOL_ROUNDS
                ));
        } finally {
            forkSessionManager.deleteSession(forked.sessionId());
        }
    }
}
