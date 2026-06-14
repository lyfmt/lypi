package cn.lypi.contracts.context;

import java.util.Map;

public record LegacyContentBlock(
    ContentBlockKind kind,
    String text,
    Map<String, Object> metadata
) implements ContentBlock {}
