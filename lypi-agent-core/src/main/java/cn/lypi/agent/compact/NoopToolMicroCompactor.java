package cn.lypi.agent.compact;

import java.util.List;

public final class NoopToolMicroCompactor implements ToolMicroCompactor {
    @Override
    public ToolMicroCompactResult compact(ToolMicroCompactRequest request) {
        return new ToolMicroCompactResult(request == null ? null : request.context(), List.of());
    }
}
