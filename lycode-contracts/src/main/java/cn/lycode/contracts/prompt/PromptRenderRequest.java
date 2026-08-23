package cn.lycode.contracts.prompt;

import java.util.Map;

public record PromptRenderRequest(
    String templateName,
    Map<String, String> arguments,
    String invokingCommand
) {}

