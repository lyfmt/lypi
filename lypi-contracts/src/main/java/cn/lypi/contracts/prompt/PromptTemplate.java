package cn.lypi.contracts.prompt;

import java.util.List;

public record PromptTemplate(
    String name,
    String description,
    PromptTemplateSource source,
    List<PromptParameter> parameters,
    String templateBody,
    String contentHash
) {}

