package cn.lypi.tool.builtin.subagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.subagent.ExpertAgentDefinition;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ExpertAgentResolverTest {
    @Test
    void selectedExpertProvidesConfiguredDefaults() {
        ExpertAgentResolver resolver = new ExpertAgentResolver(List.of(expert()));

        ExpertAgentResolver.Resolved resolved = resolver.resolve(Map.of("agent", "code-reviewer"));

        assertEquals(Optional.of("openai"), resolved.provider());
        assertEquals(Optional.of("gpt-5.4"), resolved.model());
        assertEquals(List.of("bash"), resolved.requestedTools());
        assertEquals(Optional.of("code-reviewer"), resolved.agentRole());
        assertEquals(Optional.of("Review code precisely."), resolved.initialSystemPrompt());
        assertEquals(List.of("code-reviewer"), resolver.names());
    }

    @Test
    void unselectedExpertKeepsGenericSpawnValues() {
        ExpertAgentResolver resolver = new ExpertAgentResolver(List.of(expert()));

        ExpertAgentResolver.Resolved resolved = resolver.resolve(Map.of(
            "provider", "anthropic",
            "model", "claude-opus",
            "tools", List.of("write")
        ));

        assertEquals(Optional.of("anthropic"), resolved.provider());
        assertEquals(Optional.of("claude-opus"), resolved.model());
        assertEquals(List.of("write"), resolved.requestedTools());
        assertEquals(Optional.empty(), resolved.agentRole());
        assertEquals(Optional.empty(), resolved.initialSystemPrompt());
    }

    @Test
    void explicitValuesOverrideExpertAndEmptyToolsReplaceConfiguredTools() {
        ExpertAgentResolver resolver = new ExpertAgentResolver(List.of(expert()));

        ExpertAgentResolver.Resolved resolved = resolver.resolve(Map.of(
            "agent", "code-reviewer",
            "provider", " ",
            "model", "gpt-5.4-mini",
            "tools", List.of()
        ));

        assertEquals(Optional.of("openai"), resolved.provider());
        assertEquals(Optional.of("gpt-5.4-mini"), resolved.model());
        assertEquals(List.of(), resolved.requestedTools());
        assertEquals(Optional.of("code-reviewer"), resolved.agentRole());
        assertEquals(Optional.of("Review code precisely."), resolved.initialSystemPrompt());
    }

    @Test
    void unknownOrDuplicateExpertNamesFailFast() {
        ExpertAgentResolver resolver = new ExpertAgentResolver(List.of(expert()));

        IllegalArgumentException unknown = assertThrows(
            IllegalArgumentException.class,
            () -> resolver.resolve(Map.of("agent", "missing-agent"))
        );
        IllegalArgumentException duplicate = assertThrows(
            IllegalArgumentException.class,
            () -> new ExpertAgentResolver(List.of(expert(), expert()))
        );

        assertTrue(unknown.getMessage().contains("missing-agent"));
        assertTrue(unknown.getMessage().contains("code-reviewer"));
        assertTrue(duplicate.getMessage().contains("code-reviewer"));
    }

    private ExpertAgentDefinition expert() {
        return new ExpertAgentDefinition(
            "code-reviewer",
            "openai",
            "gpt-5.4",
            "Review code precisely.",
            List.of("bash"),
            Path.of("/repo/.ly-pi/agents/code-reviewer.yaml")
        );
    }
}
