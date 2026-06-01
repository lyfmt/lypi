package cn.lypi.contracts.context;

import cn.lypi.contracts.model.TokenUsage;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public record AgentMessage(
    String id,
    MessageRole role,
    MessageKind kind,
    List<ContentBlock> content,
    Instant timestamp,
    Optional<TokenUsage> usage,
    Optional<String> stopReason
) {}

