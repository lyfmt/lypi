package cn.lypi.tool.web;

import java.util.Optional;

/**
 * 表示缓存中的单条 Web 内容。
 */
public record WebStoredItem(
    String url,
    Optional<String> title,
    Optional<String> snippet,
    String content,
    Optional<String> format,
    boolean truncated,
    Optional<String> source
) {
    public WebStoredItem {
        url = url == null ? "" : url;
        title = title == null ? Optional.empty() : title;
        snippet = snippet == null ? Optional.empty() : snippet;
        content = content == null ? "" : content;
        format = format == null ? Optional.empty() : format;
        source = source == null ? Optional.empty() : source;
    }
}
