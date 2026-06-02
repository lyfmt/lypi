package cn.lypi.resource;

import cn.lypi.contracts.prompt.SystemPrompt;
import cn.lypi.contracts.prompt.PromptParameter;
import cn.lypi.contracts.resource.ContextFile;
import cn.lypi.contracts.resource.MemorySource;
import cn.lypi.contracts.resource.ResourceSnapshot;
import cn.lypi.contracts.skill.SkillDescriptor;
import java.util.ArrayList;
import java.util.List;

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
            content.append("## Available Skills\n");
            for (SkillDescriptor skill : resources.skillIndex().skills()) {
                sourceNames.add("skill:" + skill.name());
                content.append("- skill:")
                    .append(skill.name())
                    .append(" source=")
                    .append(skill.source())
                    .append(" hash=")
                    .append(skill.contentHash())
                    .append('\n')
                    .append("  description: ")
                    .append(skill.description())
                    .append('\n');
                if (!skill.pathGlobs().isEmpty()) {
                    content.append("  paths: ").append(skill.pathGlobs()).append('\n');
                }
            }
            content.append('\n');
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
}
