package cn.lycode.contracts.boundary;

public record CapabilityBoundary(
    String name,
    String description,
    BoundaryStatus status,
    String reason
) {}

