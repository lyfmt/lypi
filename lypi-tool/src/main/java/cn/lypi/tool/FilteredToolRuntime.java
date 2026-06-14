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
        return isAllowed(resolved.get().name()) ? resolved : Optional.empty();
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
            if (resolved.isEmpty() || !isAllowed(resolved.get().name())) {
                results.add(errorResult(request, resolved.map(Tool::name).orElse(request.toolName())));
                continue;
            }
            results.add(delegate.execute(List.of(request), context, invocation).getFirst());
        }
        return List.copyOf(results);
    }

    private boolean isAllowed(String canonicalName) {
        return canonicalName != null && effectiveTools.contains(canonicalName);
    }

    private ToolResult<String> errorResult(ToolUseRequest request, String canonicalName) {
        String toolUseId = request.toolUseId();
        String message = "Tool is not allowed for this subagent: " + canonicalName;
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
