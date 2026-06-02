package cn.lypi.resource;

import cn.lypi.contracts.prompt.PromptRenderRequest;
import cn.lypi.contracts.prompt.PromptTemplate;

/**
 * 渲染 Prompt Template 文本。
 */
public interface PromptRenderer {
    /**
     * 渲染 Prompt Template。
     *
     * NOTE: 只生成文本，不授予工具权限，不执行脚本。
     */
    PromptRenderResult render(PromptTemplate template, PromptRenderRequest request);
}
