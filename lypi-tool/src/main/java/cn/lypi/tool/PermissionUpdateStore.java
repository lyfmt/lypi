package cn.lypi.tool;

import cn.lypi.contracts.security.PermissionUpdate;

/**
 * 持久化用户确认后的权限规则更新。
 */
@FunctionalInterface
public interface PermissionUpdateStore {
    /**
     * 保存一次权限规则更新。
     */
    void append(PermissionUpdate update);

    /**
     * 返回不持久化任何更新的 store。
     */
    static PermissionUpdateStore noop() {
        return update -> {
        };
    }
}
