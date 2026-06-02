package cn.lypi.contracts.boundary;

public record CapabilityGuardResult(
    String capability,
    boolean allowed,
    String message
) {}
