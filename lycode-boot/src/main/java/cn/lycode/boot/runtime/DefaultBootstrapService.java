package cn.lycode.boot.runtime;

import cn.lycode.boot.BootstrapService;
import cn.lycode.contracts.bootstrap.BootstrapContext;
import cn.lycode.contracts.bootstrap.BootstrapRequest;
import cn.lycode.contracts.bootstrap.ProjectSettings;
import cn.lycode.contracts.bootstrap.SessionSettings;
import cn.lycode.contracts.bootstrap.SettingsBundle;
import cn.lycode.contracts.bootstrap.UserSettings;
import cn.lycode.contracts.model.ModelSelection;
import cn.lycode.contracts.prompt.SystemPrompt;
import cn.lycode.contracts.resource.ResourceSnapshot;
import cn.lycode.contracts.runtime.ResourceRuntimePort;
import cn.lycode.contracts.runtime.SessionManagerPort;
import cn.lycode.contracts.runtime.ToolRuntimePort;
import cn.lycode.contracts.session.SessionContext;
import cn.lycode.contracts.session.SessionHandle;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * 默认启动装配服务。
 *
 * NOTE: 该服务只组合 boot 已持有的运行时端口；资源发现、session 持久化和工具快照仍由各模块负责。
 */
final class DefaultBootstrapService implements BootstrapService {
    private final LyCodeRuntimeProperties properties;
    private final SessionManagerPort sessionManager;
    private final ResourceRuntimePort resourceRuntime;
    private final ToolRuntimePort toolRuntime;

    DefaultBootstrapService(
        LyCodeRuntimeProperties properties,
        SessionManagerPort sessionManager,
        ResourceRuntimePort resourceRuntime,
        ToolRuntimePort toolRuntime
    ) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager must not be null");
        this.resourceRuntime = Objects.requireNonNull(resourceRuntime, "resourceRuntime must not be null");
        this.toolRuntime = Objects.requireNonNull(toolRuntime, "toolRuntime must not be null");
    }

    /**
     * 组装启动上下文。
     */
    @Override
    public BootstrapContext bootstrap(BootstrapRequest request) {
        BootstrapRequest safeRequest = request == null
            ? new BootstrapRequest(properties.getCwd(), java.util.List.of(), java.util.Optional.empty(), java.util.Optional.empty())
            : request;
        Path cwd = (safeRequest.cwd() == null ? properties.getCwd() : safeRequest.cwd()).toAbsolutePath().normalize();
        String sessionId = safeRequest.sessionId().orElse(properties.getSessionId());
        SessionHandle session = safeRequest.sessionId().isPresent() || properties.isSessionIdConfigured()
            ? sessionManager.openOrCreate(sessionId)
            : sessionManager.openTemporary(sessionId);
        ResourceSnapshot resources = resourceRuntime.load(cwd);
        SessionContext sessionContext = sessionManager.context(session.leafId());
        SystemPrompt systemPrompt = resourceRuntime.buildSystemPrompt(resources, sessionContext.permissionRuntimeState());
        ModelSelection modelSelection = new ModelSelection(
            properties.getDefaultProvider(),
            properties.getDefaultModel(),
            properties.getThinkingLevel()
        );
        return new BootstrapContext(
            cwd,
            cwd,
            new SettingsBundle(new UserSettings(Map.of()), new ProjectSettings(Map.of()), new SessionSettings(Map.of()), Map.of()),
            resources,
            toolRuntime.snapshot(),
            session,
            modelSelection,
            systemPrompt
        );
    }
}
