package cn.lypi.contracts.context;

import java.util.Map;

public record ContentBlock(
    ContentBlockKind kind,
    String text,
    Map<String, Object> metadata
) {}

