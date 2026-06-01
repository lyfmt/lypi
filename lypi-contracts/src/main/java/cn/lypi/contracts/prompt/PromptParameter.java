package cn.lypi.contracts.prompt;

import java.util.Optional;

public record PromptParameter(
    String name,
    String description,
    boolean required,
    Optional<String> defaultValue
) {}

