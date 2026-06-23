package cn.lypi.contracts.web;

import java.time.Instant;
import java.util.Optional;

/**
 * 表示一条 provider 无关的 Web 搜索结果。
 */
public record WebSearchResult(
    String title,
    String url,
    Optional<String> snippet,
    Optional<String> content,
    Optional<Instant> publishedAt,
    Optional<Instant> lastUpdated,
    Optional<Double> score,
    Optional<String> favicon
) {
    public WebSearchResult {
        snippet = snippet == null ? Optional.empty() : snippet;
        content = content == null ? Optional.empty() : content;
        publishedAt = publishedAt == null ? Optional.empty() : publishedAt;
        lastUpdated = lastUpdated == null ? Optional.empty() : lastUpdated;
        score = score == null ? Optional.empty() : score;
        favicon = favicon == null ? Optional.empty() : favicon;
    }
}
