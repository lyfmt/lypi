package cn.lycode.contracts.skill;

import cn.lycode.contracts.resource.ResourceDiagnostic;
import java.util.List;

public record SkillIndex(
    List<SkillDescriptor> skills,
    List<ResourceDiagnostic> diagnostics
) {}

