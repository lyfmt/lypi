package cn.lypi.contracts.boundary;

public record CapabilityBoundary(
    String name,
    String description,
    BoundaryStatus status,
    String reason
) {}

