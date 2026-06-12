package cn.lypi.resource;

import cn.lypi.contracts.prompt.SystemPrompt;
import cn.lypi.contracts.prompt.PromptParameter;
import cn.lypi.contracts.resource.ContextFile;
import cn.lypi.contracts.resource.MemorySource;
import cn.lypi.contracts.resource.ResourceSnapshot;
import cn.lypi.contracts.skill.SkillDescriptor;
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
        StringBuilder content = new StringBuilder();
        List<String> sourceNames = new ArrayList<>();

        for (ContextFile file : resources.agentFiles()) {
            String sourceName = file.path().toString();
            sourceNames.add(sourceName);
            content.append("## ").append(sourceName).append('\n');
            content.append(file.content().strip()).append("\n\n");
        }

        if (!resources.memorySources().isEmpty()) {
            content.append("## Memory Sources\n");
            for (MemorySource memorySource : resources.memorySources()) {
                sourceNames.add(memorySource.path().toString());
                content.append("- ")
                    .append(memorySource.path())
                    .append(" (")
                    .append(memorySource.contentHash())
                    .append(")\n");
            }
            content.append('\n');
        }

        if (!resources.skillIndex().skills().isEmpty()) {
            appendSkills(content, sourceNames, resources.skillIndex().skills());
        }

        if (!resources.promptTemplates().isEmpty()) {
            content.append("## Prompt Templates\n");
            resources.promptTemplates().forEach(template -> {
                sourceNames.add("prompt:" + template.name());
                content.append("- prompt:")
                    .append(template.name())
                    .append(" source=")
                    .append(template.source())
                    .append(" hash=")
                    .append(template.contentHash())
                    .append('\n')
                    .append("  description: ")
                    .append(template.description())
                    .append('\n');
                if (!template.parameters().isEmpty()) {
                    content.append("  parameters: ").append(parameterSummary(template.parameters())).append('\n');
                }
            });
            content.append('\n');
        }

        String promptContent = content.toString().strip();
        return new SystemPrompt(promptContent, List.copyOf(sourceNames), Hashing.sha256(promptContent));
    }

    private String parameterSummary(List<PromptParameter> parameters) {
        return parameters.stream()
            .map(parameter -> parameter.name() + (parameter.required() ? "(required)" : "(optional)"))
            .reduce((left, right) -> left + ", " + right)
            .orElse("");
    }

    private void appendSkills(StringBuilder content, List<String> sourceNames, List<SkillDescriptor> skills) {
        content.append("## Skills\n");
        content.append("A skill is a set of local instructions to follow that is stored in a `SKILL.md` file.\n\n");
        content.append("### Available skills\n");
        for (SkillDescriptor skill : skills) {
            sourceNames.add("skill:" + skill.name());
            content.append("- ")
                .append(skill.name())
                .append(": ")
                .append(skill.description())
                .append(" (file: ")
                .append(skill.skillFile())
                .append(")\n");
        }
        content.append("\n");
        content.append("### How to use skills\n");
        content.append("- Discovery: The list above is the skills available in this session.\n");
        content.append("- Trigger rules: If the user names a skill with `$skillName`, you must use that skill for that turn.\n");
        content.append("- Progressive disclosure: After deciding to use a skill, read its `SKILL.md` before following it.\n");
        content.append("- Skill bodies are not included in this index. Use normal file-reading tools for implicit skill use.\n\n");
    }
}
