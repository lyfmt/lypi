package cn.lypi.resource;

import cn.lypi.contracts.skill.SkillDescriptor;
import java.util.List;

/**
 * 渲染 Skill 索引和渐进披露规则。
 */
final class SkillPromptSection implements SystemPromptSection {
    private final List<SkillDescriptor> skills;

    SkillPromptSection(List<SkillDescriptor> skills) {
        this.skills = skills == null ? List.of() : List.copyOf(skills);
    }

    @Override
    public void appendTo(StringBuilder content, List<String> sourceNames) {
        if (skills.isEmpty()) {
            return;
        }

        content.append("## Skills\n");
        content.append("A skill is a set of local instructions to follow that is stored in a `SKILL.md` file.\n\n");
        content.append("### Available skills\n");
        for (SkillDescriptor skill : skills) {
            sourceNames.add("skill:" + skill.name());
            content.append("- ")
                .append(skill.name())
                .append(": ")
                .append(skill.description())
                .append(" (file: ")
                .append(skill.skillFile())
                .append(")\n");
        }
        content.append("\n");
        content.append("### How to use skills\n");
        content.append("- Discovery: The list above is the skills available in this session.\n");
        content.append("- Trigger rules: If the user names a skill with `$skillName`, you must use that skill for that turn.\n");
        content.append("- Progressive disclosure: After deciding to use a skill, read its `SKILL.md` before following it.\n");
        content.append("- Skill bodies are not included in this index. Use normal file-reading tools for implicit skill use.\n\n");
    }
}
