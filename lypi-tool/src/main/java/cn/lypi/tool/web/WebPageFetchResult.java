package cn.lypi.tool.web;

/**
 * 本地网页抓取结果。
 */
public record WebPageFetchResult(
    String finalUrl,
    String contentType,
    String body,
    String source
) {
    public WebPageFetchResult(String finalUrl, String contentType, String body) {
        this(finalUrl, contentType, body, "local");
    }

    public WebPageFetchResult {
        finalUrl = finalUrl == null ? "" : finalUrl;
        contentType = contentType == null ? "" : contentType;
        body = body == null ? "" : body;
        source = source == null || source.isBlank() ? "local" : source;
    }
}
