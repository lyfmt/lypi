package cn.lypi.tool.web;

import cn.lypi.contracts.web.WebSearchResponse;

/**
 * 执行 Web 搜索的 provider 适配接口。
 */
public interface WebSearchProvider {
    /**
     * 返回 provider 名称。
     */
    String name();

    /**
     * 执行搜索。
     */
    WebSearchResponse search(WebSearchRequest request);
}
