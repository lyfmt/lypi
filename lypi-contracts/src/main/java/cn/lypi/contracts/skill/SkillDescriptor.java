package cn.lypi.contracts.skill;

import java.nio.file.Path;
import java.util.List;

public record SkillDescriptor(
    String name,
    String description,
    SkillSource source,
    Path skillFile,
    List<String> pathGlobs,
    List<String> allowedTools,
    String contentHash
) {}

