package cn.lypi.resource;

import cn.lypi.contracts.prompt.SystemPrompt;
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
                content.append("- ")
                    .append(skill.name())
                    .append(": ")
                    .append(skill.description());
                if (!skill.pathGlobs().isEmpty()) {
                    content.append(" paths=").append(skill.pathGlobs());
                }
                content.append('\n');
            }
            content.append('\n');
        }

        if (!resources.promptTemplates().isEmpty()) {
            content.append("## Prompt Templates\n");
            resources.promptTemplates().forEach(template -> {
                sourceNames.add("prompt:" + template.name());
                content.append("- ")
                    .append(template.name())
                    .append(": ")
                    .append(template.description())
                    .append('\n');
            });
            content.append('\n');
        }

        String promptContent = content.toString().strip();
        return new SystemPrompt(promptContent, List.copyOf(sourceNames), Hashing.sha256(promptContent));
    }
}
