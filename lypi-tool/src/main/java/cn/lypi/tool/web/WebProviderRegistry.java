package cn.lypi.tool.web;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 根据 provider 名称选择 Web 搜索和抓取实现。
 */
public final class WebProviderRegistry {
    private final String defaultProvider;
    private final Map<String, WebSearchProvider> searchProviders;
    private final Map<String, WebFetchProvider> fetchProviders;

    public WebProviderRegistry(
        String defaultProvider,
        Map<String, WebSearchProvider> searchProviders,
        Map<String, WebFetchProvider> fetchProviders
    ) {
        this.defaultProvider = normalize(defaultProvider == null || defaultProvider.isBlank() ? "tavily" : defaultProvider);
        this.searchProviders = copySearchProviders(searchProviders);
        this.fetchProviders = copyFetchProviders(fetchProviders);
    }

    /**
     * 返回搜索 provider。
     */
    public WebSearchProvider searchProvider(Optional<String> requestedProvider) {
        String name = requestedProvider.map(WebProviderRegistry::normalize).orElse(defaultSearchProvider());
        WebSearchProvider provider = searchProviders.get(name);
        if (provider == null) {
            throw new IllegalArgumentException("未知 web search provider: " + name + "，可用: " + searchProviderNames());
        }
        return provider;
    }

    /**
     * 返回抓取 provider。
     */
    public WebFetchProvider fetchProvider(Optional<String> requestedProvider) {
        String name = requestedProvider.map(WebProviderRegistry::normalize).orElse(defaultFetchProvider());
        WebFetchProvider provider = fetchProviders.get(name);
        if (provider == null) {
            throw new IllegalArgumentException("未知 web fetch provider: " + name + "，可用: " + fetchProviderNames());
        }
        return provider;
    }

    /**
     * 返回可用搜索 provider 名称。
     */
    public List<String> searchProviderNames() {
        return sortedNames(searchProviders);
    }

    /**
     * 返回可用抓取 provider 名称。
     */
    public List<String> fetchProviderNames() {
        return sortedNames(fetchProviders);
    }

    private String defaultSearchProvider() {
        if (searchProviders.containsKey(defaultProvider)) {
            return defaultProvider;
        }
        return searchProviderNames().stream()
            .findFirst()
            .orElse(defaultProvider);
    }

    private String defaultFetchProvider() {
        if (fetchProviders.containsKey(defaultProvider)) {
            return defaultProvider;
        }
        return fetchProviderNames().stream()
            .findFirst()
            .orElse(defaultProvider);
    }

    private static Map<String, WebSearchProvider> copySearchProviders(Map<String, WebSearchProvider> providers) {
        Map<String, WebSearchProvider> copy = new LinkedHashMap<>();
        if (providers != null) {
            providers.forEach((name, provider) -> {
                if (provider != null) {
                    copy.put(normalize(name), provider);
                }
            });
        }
        return Map.copyOf(copy);
    }

    private static Map<String, WebFetchProvider> copyFetchProviders(Map<String, WebFetchProvider> providers) {
        Map<String, WebFetchProvider> copy = new LinkedHashMap<>();
        if (providers != null) {
            providers.forEach((name, provider) -> {
                if (provider != null) {
                    copy.put(normalize(name), provider);
                }
            });
        }
        return Map.copyOf(copy);
    }

    private static String normalize(String provider) {
        return provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
    }

    private static List<String> sortedNames(Map<String, ?> providers) {
        List<String> names = new ArrayList<>(providers.keySet());
        names.sort(Comparator.naturalOrder());
        return List.copyOf(names);
    }
}
