package cn.lycode.tool;

import cn.lycode.contracts.agent.SteeringMessageSource;
import cn.lycode.contracts.tool.ToolUseContext;

public final class ToolSteeringSupport {
    public static final String METADATA_STEERING_MESSAGES = "steeringMessages";

    private ToolSteeringSupport() {
    }

    public static SteeringMessageSource source(ToolUseContext context) {
        if (context == null || context.metadata() == null) {
            return SteeringMessageSource.none();
        }
        Object value = context.metadata().get(METADATA_STEERING_MESSAGES);
        return value instanceof SteeringMessageSource source ? source : SteeringMessageSource.none();
    }
}
