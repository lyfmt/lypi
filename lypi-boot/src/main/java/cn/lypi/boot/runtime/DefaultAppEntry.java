package cn.lypi.boot.runtime;

import cn.lypi.boot.BootstrapService;
import cn.lypi.contracts.agent.TurnRequest;
import cn.lypi.contracts.bootstrap.BootstrapContext;
import cn.lypi.contracts.bootstrap.BootstrapRequest;
import cn.lypi.contracts.common.AbortSignal;
import cn.lypi.contracts.context.ContextBudget;
import cn.lypi.contracts.event.EventBus;
import cn.lypi.contracts.runtime.AgentCorePort;
import cn.lypi.contracts.runtime.AppEntry;
import cn.lypi.contracts.tui.SessionRuntimeState;
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
    private final LyPiRuntimeProperties properties;
    private final List<TransportLauncher> transportLaunchers;

    DefaultAppEntry(
        BootstrapService bootstrapService,
        AgentCorePort agentCore,
        EventBus eventBus,
        LyPiRuntimeProperties properties,
        List<TransportLauncher> transportLaunchers
    ) {
        this.bootstrapService = Objects.requireNonNull(bootstrapService, "bootstrapService must not be null");
        this.agentCore = Objects.requireNonNull(agentCore, "agentCore must not be null");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.transportLaunchers = List.copyOf(transportLaunchers);
    }

    /**
     * 启动应用入口。
     */
    @Override
    public void start(BootstrapRequest request) {
        BootstrapContext context = bootstrapService.bootstrap(request);
        Optional<String> initialPrompt = request == null
            ? Optional.empty()
            : request.initialPrompt().filter(prompt -> !prompt.isBlank());
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
        launcher.launch(new SessionRuntimeState(
            context.session().sessionId(),
            context.cwd(),
            context.session().leafId(),
            context.modelSelection(),
            context.modelSelection().thinkingLevel(),
            properties.getAgentMode(),
            properties.getPermissionMode(),
            new ContextBudget(0, 128_000, 100_000, 8_192, 16_384, 0L, 0L, BigDecimal.ZERO),
            false
        ), agentCore, eventBus);
    }
}
