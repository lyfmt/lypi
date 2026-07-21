package cn.lypi.contracts.agent;

import cn.lypi.contracts.skill.SkillMention;
import java.util.List;

/** A user message accepted while the current turn is still active. */
public record SteeringMessage(String userInput, List<SkillMention> skillMentions) {
    public SteeringMessage {
        userInput = userInput == null ? "" : userInput;
        skillMentions = skillMentions == null ? List.of() : List.copyOf(skillMentions);
    }
}
