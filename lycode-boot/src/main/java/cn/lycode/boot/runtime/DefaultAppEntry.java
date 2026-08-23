package cn.lycode.boot.runtime;

import cn.lycode.boot.BootstrapService;
import cn.lycode.contracts.agent.TurnRequest;
import cn.lycode.contracts.bootstrap.BootstrapContext;
import cn.lycode.contracts.bootstrap.BootstrapRequest;
import cn.lycode.contracts.common.AbortSignal;
import cn.lycode.contracts.context.ContextBudget;
import cn.lycode.contracts.event.EventBus;
import cn.lycode.contracts.runtime.AgentCorePort;
import cn.lycode.contracts.runtime.AppEntry;
import cn.lycode.contracts.runtime.SessionManagerPort;
import cn.lycode.contracts.session.SessionContext;
import cn.lycode.contracts.tui.SessionRuntimeState;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 默认应用入口。
 *
 * NOTE: 入口负责启动编排；一轮输入的生命周期由 AgentCorePort 执行。
 */
final class DefaultAppEntry implements AppEntry {
    private static final AbortSignal NEVER_ABORT = () -> false;

    private final BootstrapService bootstrapService;
    private final AgentCorePort agentCore;
    private final EventBus eventBus;
    private final LyCodeRuntimeProperties properties;
    private final SessionManagerPort sessionManager;
    private final List<TransportLauncher> transportLaunchers;

    DefaultAppEntry(
        BootstrapService bootstrapService,
        AgentCorePort agentCore,
        EventBus eventBus,
        LyCodeRuntimeProperties properties,
        SessionManagerPort sessionManager,
        List<TransportLauncher> transportLaunchers
    ) {
        this.bootstrapService = Objects.requireNonNull(bootstrapService, "bootstrapService must not be null");
        this.agentCore = Objects.requireNonNull(agentCore, "agentCore must not be null");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager must not be null");
        this.transportLaunchers = List.copyOf(transportLaunchers);
    }

    /**
     * 启动应用入口。
     */
    @Override
    public void start(BootstrapRequest request) {
        BootstrapContext context = bootstrapService.bootstrap(request);
        Optional<String> initialPrompt = initialPrompt(request);
        initialPrompt.ifPresent(prompt -> agentCore.execute(new TurnRequest(
                context.session().sessionId(),
                prompt,
                Optional.empty(),
                NEVER_ABORT
            )));
        if (initialPrompt.isEmpty() && "tui".equalsIgnoreCase(properties.getTransport())) {
            launchTui(context);
        }
    }

    private void launchTui(BootstrapContext context) {
        TransportLauncher launcher = transportLaunchers.stream()
            .filter(candidate -> "tui".equalsIgnoreCase(candidate.name()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("TUI transport launcher is not available"));
        SessionContext sessionContext = sessionContext(context);
        launcher.launch(new SessionRuntimeState(
            context.session().sessionId(),
            context.cwd(),
            context.session().leafId(),
            sessionContext.model(),
            sessionContext.thinkingLevel(),
            sessionContext.mode(),
            sessionContext.permissionRuntimeState(),
            new ContextBudget(0, 128_000, 100_000, 8_192, 16_384, 0L, 0L, BigDecimal.ZERO),
            List.of(),
            false,
            false,
            false,
            false
        ), agentCore, eventBus);
    }

    private SessionContext sessionContext(BootstrapContext context) {
        return sessionManager.context(context.session().leafId());
    }

    private Optional<String> initialPrompt(BootstrapRequest request) {
        if (request == null) {
            return Optional.empty();
        }
        Optional<String> configuredPrompt = request.initialPrompt().filter(prompt -> !prompt.isBlank());
        if (configuredPrompt.isPresent()) {
            return configuredPrompt;
        }
        String cliPrompt = String.join(" ", request.cliArgs()).trim();
        if (cliPrompt.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(cliPrompt);
    }
}
