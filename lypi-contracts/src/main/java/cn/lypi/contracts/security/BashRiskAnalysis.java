package cn.lypi.contracts.security;

import java.nio.file.Path;
import java.util.List;

public record BashRiskAnalysis(
    String normalizedCommand,
    List<String> parsedCommands,
    List<Path> redirectTargets,
    BashRiskLevel riskLevel,
    List<String> reasons,
    boolean staticallyKnown
) {}

