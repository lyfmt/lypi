package cn.lypi.tool.shell;

import cn.lypi.contracts.runtime.NetworkMode;

/**
 * 定义默认沙盒策略生成选项。
 */
public record SandboxPolicyOptions(
    NetworkMode networkMode,
    boolean failIfUnavailable
) {
    public SandboxPolicyOptions {
        networkMode = networkMode == null ? NetworkMode.DISABLED : networkMode;
    }

    /**
     * 返回第一版沙盒默认策略选项。
     */
    public static SandboxPolicyOptions defaults() {
        return new SandboxPolicyOptions(NetworkMode.DISABLED, false);
    }
}
