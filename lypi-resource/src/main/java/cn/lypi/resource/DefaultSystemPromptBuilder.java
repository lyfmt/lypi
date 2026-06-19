package cn.lypi.resource;

import cn.lypi.contracts.prompt.SystemPrompt;
import cn.lypi.contracts.resource.ContextFile;
import cn.lypi.contracts.resource.ResourceSnapshot;
import cn.lypi.contracts.security.PermissionRuntimeState;
import java.util.ArrayList;
import java.util.List;

/**
 * 默认 system prompt 构建器。
 *
 * NOTE: ContextFile 正文会进入 prompt，Skill 和 Prompt Template 只披露索引信息。
 */
public class DefaultSystemPromptBuilder implements SystemPromptBuilder {
    @Override
    public SystemPrompt build(ResourceSnapshot resources) {
        return build(resources, null);
    }

    @Override
    public SystemPrompt build(ResourceSnapshot resources, PermissionRuntimeState permissionRuntimeState) {
        StringBuilder content = new StringBuilder();
        List<String> sourceNames = new ArrayList<>();

        new BaseAgentPromptSection().appendTo(content, sourceNames);
        new PermissionPromptSection(permissionRuntimeState).appendTo(content, sourceNames);
        appendContextFiles(content, sourceNames, resources.agentFiles());
        new MemoryPromptSection(resources.memorySources()).appendTo(content, sourceNames);
        new SkillPromptSection(resources.skillIndex().skills()).appendTo(content, sourceNames);
        new PromptTemplatePromptSection(resources.promptTemplates()).appendTo(content, sourceNames);

        String promptContent = content.toString().strip();
        return new SystemPrompt(promptContent, List.copyOf(sourceNames), Hashing.sha256(promptContent));
    }

    private void appendContextFiles(StringBuilder content, List<String> sourceNames, List<ContextFile> agentFiles) {
        for (ContextFile file : agentFiles) {
            String sourceName = file.path().toString();
            sourceNames.add(sourceName);
            content.append("## ").append(sourceName).append('\n');
            content.append(file.content().strip()).append("\n\n");
        }
    }
}
