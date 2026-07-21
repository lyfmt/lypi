package cn.lypi.boot.runtime;

import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.PermissionMode;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "lypi.runtime")
public class LyPiRuntimeProperties {
    private Path cwd = Path.of(".").toAbsolutePath().normalize();
    private String sessionId;
    private boolean sessionIdConfigured;
    private String defaultProvider = "openai";
    private String defaultModel = "gpt-5-mini";
    private ThinkingLevel thinkingLevel = ThinkingLevel.MEDIUM;
    private AgentMode agentMode = AgentMode.EXECUTE;
    private PermissionMode permissionMode = PermissionMode.ASK;
    private String transport = "headless";
    private String initialPrompt;

    public Path getCwd() {
        return cwd;
    }

    public void setCwd(Path cwd) {
        this.cwd = cwd == null ? Path.of(".").toAbsolutePath().normalize() : cwd.toAbsolutePath().normalize();
    }

    public String getSessionId() {
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = "session_" + UUID.randomUUID().toString().replace("-", "");
        }
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionIdConfigured = sessionId != null && !sessionId.isBlank();
        this.sessionId = sessionIdConfigured ? sessionId : null;
    }

    public boolean isSessionIdConfigured() {
        return sessionIdConfigured;
    }

    public String getDefaultProvider() {
        return defaultProvider;
    }

    public void setDefaultProvider(String defaultProvider) {
        this.defaultProvider = defaultProvider == null || defaultProvider.isBlank() ? "openai" : defaultProvider;
    }

    public String getDefaultModel() {
        return defaultModel;
    }

    public void setDefaultModel(String defaultModel) {
        this.defaultModel = defaultModel == null || defaultModel.isBlank() ? "gpt-5-mini" : defaultModel;
    }

    public ThinkingLevel getThinkingLevel() {
        return thinkingLevel;
    }

    public void setThinkingLevel(ThinkingLevel thinkingLevel) {
        this.thinkingLevel = thinkingLevel == null ? ThinkingLevel.MEDIUM : thinkingLevel;
    }

    public AgentMode getAgentMode() {
        return agentMode;
    }

    public void setAgentMode(AgentMode agentMode) {
        this.agentMode = agentMode == null ? AgentMode.EXECUTE : agentMode;
    }

    public PermissionMode getPermissionMode() {
        return permissionMode;
    }

    public void setPermissionMode(PermissionMode permissionMode) {
        this.permissionMode = permissionMode == null ? PermissionMode.ASK : permissionMode;
    }

    public String getTransport() {
        return transport;
    }

    public void setTransport(String transport) {
        this.transport = transport == null || transport.isBlank() ? "headless" : transport;
    }

    public String getInitialPrompt() {
        return initialPrompt;
    }

    public void setInitialPrompt(String initialPrompt) {
        this.initialPrompt = initialPrompt;
    }
}
