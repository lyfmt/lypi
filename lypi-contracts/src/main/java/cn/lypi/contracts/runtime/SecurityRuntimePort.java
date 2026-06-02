package cn.lypi.contracts.runtime;

import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.tool.ToolUseContext;
import cn.lypi.contracts.tool.ToolUseRequest;

public interface SecurityRuntimePort {
    /**
     * TODO: 对工具调用进行权限判定。
     *
     * 判定流水线必须遵守 deny 优先、硬安全线不可绕过和可解释原则。
     */
    PermissionDecision decide(ToolUseRequest request, ToolUseContext context);
}
