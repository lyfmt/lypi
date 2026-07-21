package cn.lypi.tool;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.ToolResultContentBlock;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.model.TokenUsage;
import cn.lypi.contracts.runtime.ToolRuntimeInvocation;
import cn.lypi.contracts.runtime.ToolRuntimePort;
import cn.lypi.contracts.subagent.SubagentToolPolicy;
import cn.lypi.contracts.tool.Tool;
import cn.lypi.contracts.tool.ToolRegistrySnapshot;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseRequest;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 按子 Agent 工具策略过滤工具运行时。
 */
public final class FilteredToolRuntime implements ToolRuntimePort {
    private final ToolRuntimePort delegate;
    private final Set<String> effectiveTools;

    public FilteredToolRuntime(ToolRuntimePort delegate, SubagentToolPolicy toolPolicy) {
        this.delegate = java.util.Objects.requireNonNull(delegate, "delegate must not be null");
        SubagentToolPolicy normalizedPolicy = toolPolicy == null ? SubagentToolPolicy.empty() : toolPolicy;
        this.effectiveTools = new LinkedHashSet<>(normalizedPolicy.effectiveTools());
        for (String name : effectiveTools) {
            Tool<?, ?> tool = delegate.resolve(name)
                .orElseThrow(() -> new IllegalArgumentException("Unknown subagent tool: " + name));
            if (!name.equals(tool.name())) {
                throw new IllegalArgumentException(
                    "Subagent tool policy requires canonical names; " + name + " is an alias for " + tool.name()
                );
            }
        }
    }

    @Override
    public void register(Tool<?, ?> tool) {
        delegate.register(tool);
    }

    @Override
    public Optional<Tool<?, ?>> resolve(String nameOrAlias) {
        Optional<Tool<?, ?>> resolved = delegate.resolve(nameOrAlias);
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        return isAllowed(nameOrAlias) && nameOrAlias.equals(resolved.get().name()) ? resolved : Optional.empty();
    }

    @Override
    public ToolRegistrySnapshot snapshot() {
        return new ToolRegistrySnapshot(delegate.snapshot().tools().stream()
            .filter(tool -> effectiveTools.contains(tool.name()))
            .toList());
    }

    @Override
    public Path cwd() {
        return delegate.cwd();
    }

    @Override
    public List<ToolResult<?>> execute(List<ToolUseRequest> requests, ContextSnapshot context) {
        return execute(requests, context, null);
    }

    @Override
    public List<ToolResult<?>> execute(
        List<ToolUseRequest> requests,
        ContextSnapshot context,
        ToolRuntimeInvocation invocation
    ) {
        if (requests == null || requests.isEmpty()) {
            return List.of();
        }
        List<ToolResult<?>> results = new ArrayList<>(requests.size());
        for (ToolUseRequest request : requests) {
            Optional<Tool<?, ?>> resolved = delegate.resolve(request.toolName());
            if (resolved.isEmpty()
                || !request.toolName().equals(resolved.get().name())
                || !isAllowed(request.toolName())) {
                results.add(errorResult(
                    request,
                    resolved.map(Tool::name).orElse(request.toolName()),
                    resolved.isPresent() && !request.toolName().equals(resolved.get().name())
                ));
                continue;
            }
            results.add(delegate.execute(List.of(request), context, invocation).getFirst());
        }
        return List.copyOf(results);
    }

    @Override
    public void clearTurnState(ToolRuntimeInvocation invocation) {
        delegate.clearTurnState(invocation);
    }

    private boolean isAllowed(String canonicalName) {
        return canonicalName != null && effectiveTools.contains(canonicalName);
    }

    private ToolResult<String> errorResult(ToolUseRequest request, String canonicalName, boolean alias) {
        String toolUseId = request.toolUseId();
        String message = alias
            ? "Subagent tools require canonical names; " + request.toolName() + " is an alias for " + canonicalName
            : "Tool is not allowed for this subagent: " + canonicalName;
        AgentMessage agentMessage = new AgentMessage(
            "msg_" + toolUseId,
            MessageRole.TOOL_RESULT,
            MessageKind.TOOL_RESULT,
            List.of(new ToolResultContentBlock(
                toolUseId,
                message,
                true,
                Map.of("toolName", canonicalName, "originalToolName", request.toolName())
            )),
            Instant.now(),
            Optional.<TokenUsage>empty(),
            Optional.empty()
        );
        return new ToolResult<>(message, true, List.of(agentMessage), Optional.empty());
    }
}
