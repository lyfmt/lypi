package cn.lypi.contracts.bootstrap;

import java.util.Map;

public record UserSettings(
    Map<String, Object> values
) {}

