package cn.lycode.contracts.model;

import java.util.Optional;

public record AssistantDone(
    Optional<TokenUsage> usage,
    Optional<String> stopReason
) implements AssistantStreamEvent {}

