package cn.lypi.resource;

import cn.lypi.contracts.prompt.PromptParameter;
import cn.lypi.contracts.prompt.PromptTemplate;
import java.util.List;

/**
 * 渲染 Prompt Template 索引。
 */
final class PromptTemplatePromptSection implements SystemPromptSection {
    private final List<PromptTemplate> promptTemplates;

    PromptTemplatePromptSection(List<PromptTemplate> promptTemplates) {
        this.promptTemplates = promptTemplates == null ? List.of() : List.copyOf(promptTemplates);
    }

    @Override
    public void appendTo(StringBuilder content, List<String> sourceNames) {
        if (promptTemplates.isEmpty()) {
            return;
        }

        content.append("## Prompt Templates\n");
        promptTemplates.forEach(template -> {
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

    private String parameterSummary(List<PromptParameter> parameters) {
        return parameters.stream()
            .map(parameter -> parameter.name() + (parameter.required() ? "(required)" : "(optional)"))
            .reduce((left, right) -> left + ", " + right)
            .orElse("");
    }
}
