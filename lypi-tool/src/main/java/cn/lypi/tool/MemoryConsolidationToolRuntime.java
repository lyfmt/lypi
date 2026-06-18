package cn.lypi.tool;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.ToolResultContentBlock;
import cn.lypi.contracts.model.TokenUsage;
import cn.lypi.contracts.runtime.ToolRuntimeInvocation;
import cn.lypi.contracts.runtime.ToolRuntimePort;
import cn.lypi.contracts.tool.Tool;
import cn.lypi.contracts.tool.ToolRegistrySnapshot;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseRequest;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 后台记忆沉淀专用工具运行时。
 *
 * NOTE: 只限制工具可见性和写路径；权限 ASK 由后台装配使用 deny gate 处理。
 */
public final class MemoryConsolidationToolRuntime implements ToolRuntimePort {
    private static final Set<String> ALLOWED_TOOLS = Set.of("read", "grep", "glob", "edit", "write");

    private final ToolRuntimePort delegate;
    private final MemoryConsolidationWritePolicy writePolicy;

    public MemoryConsolidationToolRuntime(ToolRuntimePort delegate, MemoryConsolidationWritePolicy writePolicy) {
        this.delegate = java.util.Objects.requireNonNull(delegate, "delegate must not be null");
        this.writePolicy = java.util.Objects.requireNonNull(writePolicy, "writePolicy must not be null");
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
        return isAllowedTool(resolved.get().name()) ? resolved : Optional.empty();
    }

    @Override
    public ToolRegistrySnapshot snapshot() {
        return new ToolRegistrySnapshot(delegate.snapshot().tools().stream()
            .filter(tool -> isAllowedTool(tool.name()))
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
            String canonicalName = resolved.map(Tool::name).orElse(request.toolName());
            if (resolved.isEmpty() || !isAllowedTool(canonicalName)) {
                results.add(errorResult(request, "Memory consolidation denied tool: " + canonicalName));
                continue;
            }
            if (isWriteTool(canonicalName) && !writePolicy.isAllowedWritePath(pathInput(request))) {
                results.add(errorResult(request, "Memory consolidation denied write path: " + pathInput(request)));
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

    private boolean isAllowedTool(String toolName) {
        return ALLOWED_TOOLS.contains(toolName);
    }

    private boolean isWriteTool(String toolName) {
        return "edit".equals(toolName) || "write".equals(toolName);
    }

    private String pathInput(ToolUseRequest request) {
        Object value = request.input() == null ? null : request.input().get("path");
        return value == null ? "" : value.toString();
    }

    private ToolResult<String> errorResult(ToolUseRequest request, String message) {
        String toolUseId = request.toolUseId();
        AgentMessage agentMessage = new AgentMessage(
            "msg_" + toolUseId,
            MessageRole.TOOL_RESULT,
            MessageKind.TOOL_RESULT,
            List.of(new ToolResultContentBlock(
                toolUseId,
                message,
                true,
                Map.of("toolName", request.toolName())
            )),
            Instant.now(),
            Optional.<TokenUsage>empty(),
            Optional.empty()
        );
        return new ToolResult<>(message, true, List.of(agentMessage), Optional.empty());
    }
}
