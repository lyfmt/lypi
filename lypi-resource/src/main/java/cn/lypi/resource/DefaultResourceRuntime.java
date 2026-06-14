package cn.lypi.resource;

import cn.lypi.contracts.prompt.PromptRenderRequest;
import cn.lypi.contracts.prompt.PromptRenderResult;
import cn.lypi.contracts.prompt.PromptTemplate;
import cn.lypi.contracts.prompt.SystemPrompt;
import cn.lypi.contracts.resource.ResourceSnapshot;
import cn.lypi.contracts.runtime.ResourceRuntimePort;
import java.nio.file.Path;

/**
 * 资源运行时端口的默认适配器。
 *
 * NOTE: 该适配器只组合资源加载和 system prompt 构建，不接入 boot 或工具注册流程。
 */
public class DefaultResourceRuntime implements ResourceRuntimePort {
    private final ResourceLoader resourceLoader;
    private final SystemPromptBuilder systemPromptBuilder;
    private final PromptRenderer promptRenderer;

    public DefaultResourceRuntime() {
        this(new DefaultResourceLoader(), new DefaultSystemPromptBuilder());
    }

    public DefaultResourceRuntime(ResourceLoader resourceLoader, SystemPromptBuilder systemPromptBuilder) {
        this(resourceLoader, systemPromptBuilder, new DefaultPromptRenderer());
    }

    DefaultResourceRuntime(ResourceLoader resourceLoader, SystemPromptBuilder systemPromptBuilder, PromptRenderer promptRenderer) {
        this.resourceLoader = resourceLoader;
        this.systemPromptBuilder = systemPromptBuilder;
        this.promptRenderer = promptRenderer;
    }

    /**
     * 加载指定目录对应的资源快照。
     */
    @Override
    public ResourceSnapshot load(Path cwd) {
        return resourceLoader.load(cwd);
    }

    /**
     * 根据已加载资源构建 system prompt。
     */
    @Override
    public SystemPrompt buildSystemPrompt(ResourceSnapshot resources) {
        return systemPromptBuilder.build(resources);
    }

    @Override
    public PromptRenderResult renderPrompt(PromptTemplate template, PromptRenderRequest request) {
        return promptRenderer.render(template, request);
    }
}
