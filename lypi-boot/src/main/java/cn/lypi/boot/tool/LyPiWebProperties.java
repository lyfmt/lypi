package cn.lypi.boot.tool;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "lypi.web")
public class LyPiWebProperties {
    private boolean enabled;
    private String defaultProvider = "tavily";
    private Duration timeout = Duration.ofSeconds(20);
    private int maxResults = 10;
    private CacheProperties cache = new CacheProperties();
    private FetchProperties fetch = new FetchProperties();
    private Map<String, ProviderProperties> providers = defaultProviders();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getDefaultProvider() {
        return defaultProvider;
    }

    public void setDefaultProvider(String defaultProvider) {
        this.defaultProvider = defaultProvider == null || defaultProvider.isBlank() ? "tavily" : defaultProvider;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout == null ? Duration.ofSeconds(20) : timeout;
    }

    public void setTimeoutSeconds(long timeoutSeconds) {
        this.timeout = timeoutSeconds <= 0 ? Duration.ofSeconds(20) : Duration.ofSeconds(timeoutSeconds);
    }

    public int getMaxResults() {
        return maxResults;
    }

    public void setMaxResults(int maxResults) {
        this.maxResults = Math.max(1, Math.min(10, maxResults));
    }

    public CacheProperties getCache() {
        return cache;
    }

    public void setCache(CacheProperties cache) {
        this.cache = cache == null ? new CacheProperties() : cache;
    }

    public FetchProperties getFetch() {
        return fetch;
    }

    public void setFetch(FetchProperties fetch) {
        this.fetch = fetch == null ? new FetchProperties() : fetch;
    }

    public Map<String, ProviderProperties> getProviders() {
        return providers;
    }

    public void setProviders(Map<String, ProviderProperties> providers) {
        Map<String, ProviderProperties> merged = defaultProviders();
        if (providers != null) {
            providers.forEach((name, properties) -> merged.put(name, properties == null ? new ProviderProperties() : properties));
        }
        this.providers = merged;
    }

    private static Map<String, ProviderProperties> defaultProviders() {
        Map<String, ProviderProperties> defaults = new LinkedHashMap<>();
        ProviderProperties exa = new ProviderProperties();
        exa.setEndpoint("https://mcp.exa.ai/mcp");
        defaults.put("exa", exa);

        ProviderProperties tavily = new ProviderProperties();
        tavily.setApiKeyEnv("TAVILY_API_KEY");
        defaults.put("tavily", tavily);

        ProviderProperties brave = new ProviderProperties();
        brave.setApiKeyEnv("BRAVE_SEARCH_API_KEY");
        defaults.put("brave", brave);

        ProviderProperties perplexity = new ProviderProperties();
        perplexity.setApiKeyEnv("PERPLEXITY_API_KEY");
        defaults.put("perplexity", perplexity);
        return defaults;
    }

    public static class ProviderProperties {
        private boolean enabled = true;
        private String apiKey;
        private String apiKeyEnv;
        private String endpoint;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getApiKeyEnv() {
            return apiKeyEnv;
        }

        public void setApiKeyEnv(String apiKeyEnv) {
            this.apiKeyEnv = apiKeyEnv;
        }

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }
    }

    public static class CacheProperties {
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class FetchProperties {
        private JinaProperties jina = new JinaProperties();
        private FallbackProperties fallback = new FallbackProperties();

        public JinaProperties getJina() {
            return jina;
        }

        public void setJina(JinaProperties jina) {
            this.jina = jina == null ? new JinaProperties() : jina;
        }

        public FallbackProperties getFallback() {
            return fallback;
        }

        public void setFallback(FallbackProperties fallback) {
            this.fallback = fallback == null ? new FallbackProperties() : fallback;
        }
    }

    public static class JinaProperties {
        private boolean enabled = true;
        private String endpoint = "https://r.jina.ai/http://";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint == null || endpoint.isBlank() ? "https://r.jina.ai/http://" : endpoint;
        }
    }

    public static class FallbackProperties {
        private boolean enabled = true;
        private int minBodyChars = 200;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMinBodyChars() {
            return minBodyChars;
        }

        public void setMinBodyChars(int minBodyChars) {
            this.minBodyChars = Math.max(0, minBodyChars);
        }
    }
}
