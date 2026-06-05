package cn.lypi.tool;

import cn.lypi.contracts.event.PermissionRequestEvent;
import cn.lypi.contracts.security.PermissionResponse;

/**
 * 处理结构化权限请求并返回用户选择。
 */
@FunctionalInterface
public interface PermissionResponseGate {
    /**
     * 返回一次权限请求的用户选择。
     *
     * NOTE: 实现只应渲染 `PermissionRequestEvent.options()` 并回传选项 ID，不做风险判断。
     */
    PermissionResponse request(PermissionRequestEvent requestEvent);
}
