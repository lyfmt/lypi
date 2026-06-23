package cn.lypi.contracts.web;

import java.time.Instant;
import java.util.Optional;

/**
 * 表示一次 URL 内容抽取的统一响应。
 */
public record WebFetchResponse(
    String provider,
    String url,
    Optional<String> title,
    String content,
    String format,
    Optional<Instant> fetchedAt,
    Optional<WebProviderUsage> usage
) {
    public WebFetchResponse {
        title = title == null ? Optional.empty() : title;
        fetchedAt = fetchedAt == null ? Optional.empty() : fetchedAt;
        usage = usage == null ? Optional.empty() : usage;
    }
}
