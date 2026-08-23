package cn.lycode.resource;

import cn.lycode.contracts.prompt.PromptRenderRequest;
import cn.lycode.contracts.prompt.PromptRenderResult;
import cn.lycode.contracts.prompt.PromptTemplate;

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
