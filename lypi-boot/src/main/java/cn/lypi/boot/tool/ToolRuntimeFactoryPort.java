package cn.lypi.boot.tool;

import cn.lypi.contracts.runtime.ToolRuntimePort;
import java.nio.file.Path;

/**
 * 创建绑定到指定 cwd 的工具运行时。
 */
@FunctionalInterface
public interface ToolRuntimeFactoryPort {
    /**
     * 创建绑定到 cwd 的工具运行时。
     */
    ToolRuntimePort create(Path cwd);
}
