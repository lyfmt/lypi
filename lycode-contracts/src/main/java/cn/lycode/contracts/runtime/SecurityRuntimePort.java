package cn.lycode.contracts.runtime;

import cn.lycode.contracts.security.PermissionDecision;
import cn.lycode.contracts.tool.ToolUseContext;
import cn.lycode.contracts.tool.ToolUseRequest;

public interface SecurityRuntimePort {
    /**
     * 对工具调用进行权限判定。
     *
     * NOTE: 判定流水线必须遵守 deny 优先、硬安全线不可绕过和可解释原则。
     */
    PermissionDecision decide(ToolUseRequest request, ToolUseContext context);
}
