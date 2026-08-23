package cn.lycode.contracts.prompt;

import java.util.List;

public record SystemPrompt(
    String content,
    List<String> sourceNames,
    String contentHash
) {}

