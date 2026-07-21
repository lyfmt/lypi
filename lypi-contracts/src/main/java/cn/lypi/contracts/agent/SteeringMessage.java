package cn.lypi.contracts.agent;

import cn.lypi.contracts.skill.SkillMention;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;
import java.util.Map;

/** A message accepted while the current turn is still active. */
public record SteeringMessage(
    SteeringMessageType type,
    String content,
    List<SkillMention> skillMentions,
    Map<String, Object> metadata
) {
    public SteeringMessage {
        type = type == null ? SteeringMessageType.USER : type;
        content = content == null ? "" : content;
        skillMentions = skillMentions == null ? List.of() : List.copyOf(skillMentions);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public SteeringMessage(String userInput, List<SkillMention> skillMentions) {
        this(SteeringMessageType.USER, userInput, skillMentions, Map.of());
    }

    public static SteeringMessage user(String content, List<SkillMention> skillMentions) {
        return new SteeringMessage(SteeringMessageType.USER, content, skillMentions, Map.of());
    }

    public static SteeringMessage agentCommunication(String content, Map<String, Object> metadata) {
        return new SteeringMessage(SteeringMessageType.AGENT_COMMUNICATION, content, List.of(), metadata);
    }

    @JsonIgnore
    public String userInput() {
        return content;
    }
}
