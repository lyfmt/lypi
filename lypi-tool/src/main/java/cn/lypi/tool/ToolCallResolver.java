package cn.lypi.tool;

import cn.lypi.contracts.tool.Tool;
import cn.lypi.contracts.tool.ToolUseRequest;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

final class ToolCallResolver {
    private final ToolRegistry registry;

    ToolCallResolver(ToolRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
    }

    List<ResolvedCall> resolve(List<ToolUseRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return List.of();
        }
        java.util.ArrayList<ResolvedCall> calls = new java.util.ArrayList<>(requests.size());
        for (int index = 0; index < requests.size(); index++) {
            ToolUseRequest request = requests.get(index);
            Optional<Tool<?, ?>> tool = registry.resolve(request.toolName());
            if (tool.isEmpty()) {
                calls.add(ResolvedCall.unknown(index, request));
                continue;
            }
            Tool<Map<String, Object>, ?> resolvedTool = castTool(tool.get());
            calls.add(new ResolvedCall(index, canonicalRequest(request, resolvedTool), request.toolName(), resolvedTool));
        }
        return List.copyOf(calls);
    }

    private ToolUseRequest canonicalRequest(ToolUseRequest request, Tool<Map<String, Object>, ?> tool) {
        if (Objects.equals(request.toolName(), tool.name())) {
            return request;
        }
        return new ToolUseRequest(
            request.toolUseId(),
            tool.name(),
            request.input(),
            request.parentMessageId()
        );
    }

    @SuppressWarnings("unchecked")
    private Tool<Map<String, Object>, ?> castTool(Tool<?, ?> tool) {
        return (Tool<Map<String, Object>, ?>) tool;
    }

    record ResolvedCall(
        int index,
        ToolUseRequest request,
        String originalToolName,
        Tool<Map<String, Object>, ?> tool
    ) {
        static ResolvedCall unknown(int index, ToolUseRequest request) {
            return new ResolvedCall(index, request, request.toolName(), null);
        }

        boolean known() {
            return tool != null;
        }
    }
}
