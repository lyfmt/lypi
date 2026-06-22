package cn.lypi.tool.web;

import cn.lypi.contracts.web.WebFetchResponse;

/**
 * 执行 URL 内容抽取的 provider 适配接口。
 */
public interface WebFetchProvider {
    /**
     * 返回 provider 名称。
     */
    String name();

    /**
     * 抽取 URL 内容。
     */
    WebFetchResponse fetch(WebFetchRequest request);
}
