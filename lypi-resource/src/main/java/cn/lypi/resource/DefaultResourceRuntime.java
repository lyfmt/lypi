package cn.lypi.resource;

import cn.lypi.contracts.prompt.SystemPrompt;
import cn.lypi.contracts.resource.ResourceSnapshot;
import cn.lypi.contracts.runtime.ResourceRuntimePort;
import java.nio.file.Path;

public class DefaultResourceRuntime implements ResourceRuntimePort {
    private final ResourceLoader resourceLoader;
    private final SystemPromptBuilder systemPromptBuilder;

    public DefaultResourceRuntime() {
        this(new DefaultResourceLoader(), new DefaultSystemPromptBuilder());
    }

    public DefaultResourceRuntime(ResourceLoader resourceLoader, SystemPromptBuilder systemPromptBuilder) {
        this.resourceLoader = resourceLoader;
        this.systemPromptBuilder = systemPromptBuilder;
    }

    @Override
    public ResourceSnapshot load(Path cwd) {
        return resourceLoader.load(cwd);
    }

    @Override
    public SystemPrompt buildSystemPrompt(ResourceSnapshot resources) {
        return systemPromptBuilder.build(resources);
    }
}
