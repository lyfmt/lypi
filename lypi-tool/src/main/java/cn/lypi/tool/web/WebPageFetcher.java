package cn.lypi.tool.web;

/**
 * 抓取公开网页内容。
 */
@FunctionalInterface
public interface WebPageFetcher {
    /**
     * 抓取指定 URL。
     */
    WebPageFetchResult fetch(String url);
}
