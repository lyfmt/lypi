package cn.lycode.contracts.common;

import java.util.Map;

public record JsonSchema(
    Map<String, Object> value
) {}

