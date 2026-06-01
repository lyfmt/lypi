package cn.lypi.contracts.prompt;

import java.util.List;

public record SystemPrompt(
    String content,
    List<String> sourceNames,
    String contentHash
) {}

