package cn.lypi.runtime.memory;

import java.nio.file.Path;

/**
 * 描述一次后台 memory 轻量诊断。
 */
public record MemoryLintDiagnostic(
    String code,
    Path path,
    String message
) {}
