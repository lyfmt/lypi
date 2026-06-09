package cn.lypi.tool.shell;

import cn.lypi.contracts.runtime.SandboxRuntimePolicy;
import java.nio.file.Path;

public interface SandboxPolicyResolver {
    /**
     * 生成一次命令执行使用的沙盒运行时策略。
     *
     * workspace 表示可写项目根目录，cwd 表示命令实际工作目录。
     */
    SandboxRuntimePolicy resolve(Path workspace, Path cwd);
}
