package cn.lypi.transport.tui;

import cn.lypi.contracts.skill.SkillMention;
import java.nio.file.Path;

record SkillMentionBinding(int start, int end, String name, Path skillFile) {
    SkillMention toMention() {
        return new SkillMention(name, skillFile);
    }
}
