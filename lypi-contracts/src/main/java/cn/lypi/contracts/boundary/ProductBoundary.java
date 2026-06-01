package cn.lypi.contracts.boundary;

import java.util.List;

public record ProductBoundary(
    String productName,
    String runtimeKind,
    List<DesignPrinciple> principles,
    List<CapabilityBoundary> includedCapabilities,
    List<CapabilityBoundary> excludedCapabilities
) {}

