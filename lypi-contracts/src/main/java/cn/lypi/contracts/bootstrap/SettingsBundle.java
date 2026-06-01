package cn.lypi.contracts.bootstrap;

import java.util.Map;

public record SettingsBundle(
    UserSettings user,
    ProjectSettings project,
    SessionSettings session,
    Map<String, Object> cliOverrides
) {}

