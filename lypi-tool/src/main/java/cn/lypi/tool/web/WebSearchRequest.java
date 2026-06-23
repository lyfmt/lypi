package cn.lypi.tool.web;

import java.util.List;
import java.util.Optional;

/**
 * 表示 provider 无关的 Web 搜索请求。
 */
public record WebSearchRequest(
    String query,
    int maxResults,
    List<String> allowedDomains,
    List<String> blockedDomains,
    Optional<String> recency,
    Optional<String> country,
    Optional<String> language,
    Optional<String> provider,
    boolean includeAnswer
) {
    public WebSearchRequest {
        allowedDomains = allowedDomains == null ? List.of() : List.copyOf(allowedDomains);
        blockedDomains = blockedDomains == null ? List.of() : List.copyOf(blockedDomains);
        recency = recency == null ? Optional.empty() : recency;
        country = country == null ? Optional.empty() : country;
        language = language == null ? Optional.empty() : language;
        provider = provider == null ? Optional.empty() : provider;
    }
}
