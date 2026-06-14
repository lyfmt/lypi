package cn.lypi.contracts.skill;

import java.nio.file.Path;

public record SkillMention(String name, Path skillFile) {
    public SkillMention {
        name = name == null ? "" : name;
        if (skillFile == null) {
            throw new IllegalArgumentException("skillFile must not be null");
        }
    }
}
