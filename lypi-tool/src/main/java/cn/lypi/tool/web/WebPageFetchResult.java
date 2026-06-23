package cn.lypi.tool.web;

/**
 * 本地网页抓取结果。
 */
public record WebPageFetchResult(
    String finalUrl,
    String contentType,
    String body
) {
    public WebPageFetchResult {
        finalUrl = finalUrl == null ? "" : finalUrl;
        contentType = contentType == null ? "" : contentType;
        body = body == null ? "" : body;
    }
}
