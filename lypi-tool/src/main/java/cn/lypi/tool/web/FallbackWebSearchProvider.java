package cn.lypi.tool.web;

import cn.lypi.contracts.web.WebSearchResponse;
import java.util.ArrayList;
import java.util.List;

/**
 * 按顺序尝试多个搜索 provider。
 */
public final class FallbackWebSearchProvider implements WebSearchProvider {
    private final List<WebSearchProvider> providers;

    public FallbackWebSearchProvider(List<WebSearchProvider> providers) {
        this.providers = providers == null ? List.of() : providers.stream()
            .filter(provider -> provider != null)
            .toList();
    }

    @Override
    public String name() {
        return providers.isEmpty() ? "fallback" : providers.getFirst().name();
    }

    @Override
    public WebSearchResponse search(WebSearchRequest request) {
        List<String> failures = new ArrayList<>();
        for (WebSearchProvider provider : providers) {
            try {
                return provider.search(request);
            } catch (WebProviderException exception) {
                failures.add(provider.name() + ": " + sanitize(exception.getMessage()));
            }
        }
        throw new WebProviderException("所有 web search provider 都失败: " + String.join("; ", failures));
    }

    private String sanitize(String message) {
        if (message == null || message.isBlank()) {
            return "unknown error";
        }
        return message
            .replaceAll("(?i)authorization:[^\\s]+", "authorization:[redacted]")
            .replaceAll("(?i)bearer\\s+[^\\s]+", "bearer [redacted]");
    }
}
