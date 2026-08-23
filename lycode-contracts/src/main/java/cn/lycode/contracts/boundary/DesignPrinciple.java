package cn.lycode.contracts.boundary;

public record DesignPrinciple(
    String id,
    String title,
    String description,
    PrincipleLevel level
) {}

