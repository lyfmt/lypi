package cn.lypi.contracts.context;

import java.nio.file.Path;

public record ContentReplacementRecord(
    String messageId,
    String toolUseId,
    String toolName,
    Path persistedPath,
    String preview,
    int originalTokenEstimate,
    int replacementTokenEstimate
) {}

