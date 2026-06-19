package cn.lypi.tool.shell;

import cn.lypi.contracts.security.AdditionalPermissionProfile;
import cn.lypi.contracts.runtime.SandboxRuntimePolicy;
import java.nio.file.Path;

public interface SandboxPolicyResolver {
    /**
     * 生成一次命令执行使用的沙盒运行时策略。
     *
     * workspace 表示可写项目根目录，cwd 表示命令实际工作目录。
     */
    SandboxRuntimePolicy resolve(Path workspace, Path cwd);

    /**
     * 生成携带单次额外权限的沙盒运行时策略。
     *
     * NOTE: 默认实现忽略额外权限，支持 Codex-style profile 的 resolver 应覆盖该方法。
     */
    default SandboxRuntimePolicy resolve(
        Path workspace,
        Path cwd,
        AdditionalPermissionProfile additionalPermissions
    ) {
        return resolve(workspace, cwd);
    }
}
