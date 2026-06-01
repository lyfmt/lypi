package cn.lypi.contracts.boundary;

public record DesignPrinciple(
    String id,
    String title,
    String description,
    PrincipleLevel level
) {}

