package cn.lypi.boot.ai;

import cn.lypi.ai.provider.RequestStyle;
import cn.lypi.ai.provider.TransportMode;
import cn.lypi.contracts.model.ApiStyle;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "lypi.ai")
public class LyPiAiProperties {
    private String defaultProvider;
    private String defaultModel;
    private Map<String, ProviderProperties> providers = new LinkedHashMap<>();

    public String getDefaultProvider() {
        return defaultProvider;
    }

    public void setDefaultProvider(String defaultProvider) {
        this.defaultProvider = defaultProvider;
    }

    public String getDefaultModel() {
        return defaultModel;
    }

    public void setDefaultModel(String defaultModel) {
        this.defaultModel = defaultModel;
    }

    public Map<String, ProviderProperties> getProviders() {
        return providers;
    }

    public void setProviders(Map<String, ProviderProperties> providers) {
        this.providers = providers == null ? new LinkedHashMap<>() : new LinkedHashMap<>(providers);
    }

    public static class ProviderProperties {
        private boolean enabled;
        private ApiStyle apiStyle = ApiStyle.OPENAI_COMPATIBLE;
        private RequestStyle requestStyle = RequestStyle.RESPONSES;
        private RequestStyle fallbackRequestStyle = RequestStyle.CHAT_COMPLETIONS;
        private TransportMode transport = TransportMode.AUTO;
        private URI baseUrl;
        private String websocketPath = "/v1/responses";
        private URI websocketUrl;
        private String apiKey;
        private Duration timeout = Duration.ofSeconds(30);
        private int maxRetries;
        private Map<String, Object> compat = new LinkedHashMap<>();
        private List<ModelProperties> models = new ArrayList<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public ApiStyle getApiStyle() {
            return apiStyle;
        }

        public void setApiStyle(ApiStyle apiStyle) {
            this.apiStyle = apiStyle;
        }

        public RequestStyle getRequestStyle() {
            return requestStyle;
        }

        public void setRequestStyle(RequestStyle requestStyle) {
            this.requestStyle = requestStyle;
        }

        public RequestStyle getFallbackRequestStyle() {
            return fallbackRequestStyle;
        }

        public void setFallbackRequestStyle(RequestStyle fallbackRequestStyle) {
            this.fallbackRequestStyle = fallbackRequestStyle;
        }

        public TransportMode getTransport() {
            return transport;
        }

        public void setTransport(TransportMode transport) {
            this.transport = transport;
        }

        public URI getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(URI baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getWebsocketPath() {
            return websocketPath;
        }

        public void setWebsocketPath(String websocketPath) {
            this.websocketPath = websocketPath;
        }

        public URI getWebsocketUrl() {
            return websocketUrl;
        }

        public void setWebsocketUrl(URI websocketUrl) {
            this.websocketUrl = websocketUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public int getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
        }

        public Map<String, Object> getCompat() {
            return compat;
        }

        public void setCompat(Map<String, Object> compat) {
            this.compat = compat == null ? new LinkedHashMap<>() : new LinkedHashMap<>(compat);
        }

        public List<ModelProperties> getModels() {
            return models;
        }

        public void setModels(List<ModelProperties> models) {
            this.models = models == null ? new ArrayList<>() : new ArrayList<>(models);
        }
    }

    public static class ModelProperties {
        private String modelId;
        private int contextWindow;
        private int maxOutputTokens;
        private boolean supportsThinking;
        private boolean supportsImageInput;
        private BigDecimal inputTokenCost = BigDecimal.ZERO;
        private BigDecimal outputTokenCost = BigDecimal.ZERO;
        private String currency = "USD";
        private Map<String, Object> compat = new LinkedHashMap<>();

        public String getModelId() {
            return modelId;
        }

        public void setModelId(String modelId) {
            this.modelId = modelId;
        }

        public int getContextWindow() {
            return contextWindow;
        }

        public void setContextWindow(int contextWindow) {
            this.contextWindow = contextWindow;
        }

        public int getMaxOutputTokens() {
            return maxOutputTokens;
        }

        public void setMaxOutputTokens(int maxOutputTokens) {
            this.maxOutputTokens = maxOutputTokens;
        }

        public boolean isSupportsThinking() {
            return supportsThinking;
        }

        public void setSupportsThinking(boolean supportsThinking) {
            this.supportsThinking = supportsThinking;
        }

        public boolean isSupportsImageInput() {
            return supportsImageInput;
        }

        public void setSupportsImageInput(boolean supportsImageInput) {
            this.supportsImageInput = supportsImageInput;
        }

        public BigDecimal getInputTokenCost() {
            return inputTokenCost;
        }

        public void setInputTokenCost(BigDecimal inputTokenCost) {
            this.inputTokenCost = inputTokenCost;
        }

        public BigDecimal getOutputTokenCost() {
            return outputTokenCost;
        }

        public void setOutputTokenCost(BigDecimal outputTokenCost) {
            this.outputTokenCost = outputTokenCost;
        }

        public String getCurrency() {
            return currency;
        }

        public void setCurrency(String currency) {
            this.currency = currency;
        }

        public Map<String, Object> getCompat() {
            return compat;
        }

        public void setCompat(Map<String, Object> compat) {
            this.compat = compat == null ? new LinkedHashMap<>() : new LinkedHashMap<>(compat);
        }
    }
}
