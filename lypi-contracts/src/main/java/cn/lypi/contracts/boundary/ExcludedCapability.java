package cn.lypi.contracts.boundary;

import java.util.Optional;

public record ExcludedCapability(
    String name,
    String reason,
    ExclusionKind kind,
    Optional<String> reservedInterface
) {}
