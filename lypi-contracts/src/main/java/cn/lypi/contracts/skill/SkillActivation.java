package cn.lypi.contracts.skill;

import java.time.Instant;
import java.util.List;

public record SkillActivation(
    String skillName,
    SkillSource source,
    String contentHash,
    String activatedReason,
    List<String> allowedTools,
    Instant timestamp
) {}

