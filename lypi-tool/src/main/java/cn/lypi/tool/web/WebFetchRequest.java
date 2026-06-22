package cn.lypi.tool.web;

import java.util.Optional;

/**
 * 表示 provider 无关的 URL 内容抽取请求。
 */
public record WebFetchRequest(
    String url,
    Optional<String> query,
    String format,
    int maxChars
) {
    public WebFetchRequest {
        query = query == null ? Optional.empty() : query;
    }
}
