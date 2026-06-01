package cn.lypi.security;

import cn.lypi.contracts.runtime.SecurityRuntimePort;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.tool.ToolUseContext;
import cn.lypi.contracts.tool.ToolUseRequest;

public interface PolicyEngine extends SecurityRuntimePort {
    /*
    * @status : 未完成
    * @summary : 对工具调用进行权限判定。
    *@description : 判定流水线必须遵守 deny 优先、硬安全线不可绕过和可解释原则。
    *
    *
                              */
    PermissionDecision decide(ToolUseRequest request, ToolUseContext context);
}

