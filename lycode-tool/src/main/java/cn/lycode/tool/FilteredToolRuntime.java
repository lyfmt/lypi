package cn.lycode.tool;

import cn.lycode.contracts.context.AgentMessage;
import cn.lycode.contracts.context.MessageKind;
import cn.lycode.contracts.context.MessageRole;
import cn.lycode.contracts.context.ToolResultContentBlock;
import cn.lycode.contracts.context.ContextSnapshot;
import cn.lycode.contracts.model.TokenUsage;
import cn.lycode.contracts.runtime.ToolRuntimeInvocation;
import cn.lycode.contracts.runtime.ToolRuntimePort;
import cn.lycode.contracts.subagent.SubagentToolPolicy;
import cn.lycode.contracts.tool.Tool;
import cn.lycode.contracts.tool.ToolRegistrySnapshot;
import cn.lycode.contracts.tool.ToolResult;
import cn.lycode.contracts.tool.ToolUseRequest;
import java.io.IOException;
import java.nio.file.Files;
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
        ToolRuntimeInvocation currentInvocation = invocationWithInitialCwd(invocation);
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
            ToolResult<?> result = delegate.execute(List.of(request), context, currentInvocation).getFirst();
            results.add(result);
            currentInvocation = invocationAfterResult(currentInvocation, result);
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

    private ToolRuntimeInvocation invocationWithInitialCwd(ToolRuntimeInvocation invocation) {
        if (invocation == null) {
            return null;
        }
        Path initialCwd = validStateCwd(invocation.cwd())
            .orElse(delegate.cwd().toAbsolutePath().normalize());
        return withCwd(invocation, initialCwd);
    }

    private ToolRuntimeInvocation invocationAfterResult(ToolRuntimeInvocation invocation, ToolResult<?> result) {
        if (result == null || result.stateDelta().isEmpty()) {
            return invocation;
        }
        return validStateCwd(result.stateDelta().orElseThrow().cwd())
            .map(cwd -> withCwd(invocation, cwd))
            .orElse(invocation);
    }

    private Optional<Path> validStateCwd(Path candidate) {
        if (candidate == null) {
            return Optional.empty();
        }
        Path workspaceRoot = delegate.cwd().toAbsolutePath().normalize();
        Path normalized = candidate.toAbsolutePath().normalize();
        if (!normalized.startsWith(workspaceRoot)) {
            return Optional.empty();
        }
        try {
            Path realWorkspaceRoot = workspaceRoot.toRealPath();
            Path realCandidate = normalized.toRealPath();
            if (Files.isDirectory(realCandidate) && realCandidate.startsWith(realWorkspaceRoot)) {
                return Optional.of(normalized);
            }
        } catch (IOException exception) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private ToolRuntimeInvocation withCwd(ToolRuntimeInvocation invocation, Path cwd) {
        ToolRuntimeInvocation base = invocation == null
            ? ToolRuntimeInvocation.cwdOnly(cwd)
            : invocation;
        return invocation == null ? base : base.withCwd(cwd);
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
