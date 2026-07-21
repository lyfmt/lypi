package cn.lypi.tool.builtin.subagent;

import cn.lypi.contracts.subagent.ExpertAgentDefinition;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

final class ExpertAgentResolver {
    private final Map<String, ExpertAgentDefinition> agents;
    private final List<String> names;

    ExpertAgentResolver(List<ExpertAgentDefinition> definitions) {
        Map<String, ExpertAgentDefinition> byName = new LinkedHashMap<>();
        for (ExpertAgentDefinition definition : definitions == null ? List.<ExpertAgentDefinition>of() : definitions) {
            ExpertAgentDefinition agent = Objects.requireNonNull(definition, "expert agent must not be null");
            if (byName.putIfAbsent(agent.name(), agent) != null) {
                throw new IllegalArgumentException("重复的专家 Agent: " + agent.name());
            }
        }
        this.agents = Map.copyOf(byName);
        this.names = byName.keySet().stream().sorted().toList();
    }

    List<String> names() {
        return names;
    }

    Resolved resolve(Map<String, Object> input) {
        Optional<ExpertAgentDefinition> expert = SubagentToolInputs.optionalString(input, "agent")
            .map(this::requireAgent);
        List<String> requestedTools = input != null && input.containsKey("tools")
            ? SubagentToolInputs.tools(input)
            : expert.map(ExpertAgentDefinition::tools).orElseGet(List::of);
        Optional<String> provider = SubagentToolInputs.optionalString(input, "provider")
            .or(() -> expert.map(ExpertAgentDefinition::provider));
        Optional<String> model = SubagentToolInputs.optionalString(input, "model")
            .or(() -> expert.map(ExpertAgentDefinition::model));
        return new Resolved(
            provider,
            model,
            requestedTools,
            expert.map(ExpertAgentDefinition::name),
            expert.map(ExpertAgentDefinition::prompt)
        );
    }

    private ExpertAgentDefinition requireAgent(String name) {
        ExpertAgentDefinition agent = agents.get(name);
        if (agent != null) {
            return agent;
        }
        String available = names.isEmpty() ? "无" : String.join(", ", names);
        throw new IllegalArgumentException("专家 Agent 不存在: " + name + "。可用值: " + available);
    }

    record Resolved(
        Optional<String> provider,
        Optional<String> model,
        List<String> requestedTools,
        Optional<String> agentRole,
        Optional<String> initialSystemPrompt
    ) {
        Resolved {
            provider = provider == null ? Optional.empty() : provider;
            model = model == null ? Optional.empty() : model;
            requestedTools = requestedTools == null ? List.of() : List.copyOf(requestedTools);
            agentRole = agentRole == null ? Optional.empty() : agentRole;
            initialSystemPrompt = initialSystemPrompt == null ? Optional.empty() : initialSystemPrompt;
        }
    }
}
