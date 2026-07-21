package cn.lypi.contracts.web;

import java.util.List;
import java.util.Optional;

/**
 * 表示一次 Web 搜索的统一响应。
 */
public record WebSearchResponse(
    String provider,
    String query,
    Optional<String> answer,
    List<WebSearchResult> results,
    Optional<WebProviderUsage> usage
) {
    public WebSearchResponse {
        answer = answer == null ? Optional.empty() : answer;
        results = results == null ? List.of() : List.copyOf(results);
        usage = usage == null ? Optional.empty() : usage;
    }
}
