package cn.lypi.contracts.model;

import java.net.URI;
import java.util.Map;

public record ModelDescriptor(
    String provider,
    String modelId,
    URI baseUrl,
    ApiStyle apiStyle,
    int contextWindow,
    int maxOutputTokens,
    boolean supportsThinking,
    boolean supportsImageInput,
    CostProfile costProfile,
    Map<String, Object> compat
) {}

