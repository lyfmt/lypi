package cn.lypi.contracts.common;

import java.util.Map;

public record JsonSchema(
    Map<String, Object> value
) {}

