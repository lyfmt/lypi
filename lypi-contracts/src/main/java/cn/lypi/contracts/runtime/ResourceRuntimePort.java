package cn.lypi.contracts.runtime;

import cn.lypi.contracts.prompt.SystemPrompt;
import cn.lypi.contracts.resource.ResourceSnapshot;
import java.nio.file.Path;

public interface ResourceRuntimePort {
    /**
     * 加载项目资源快照。
     *
     * NOTE: 只负责发现和解析资源，不拼接 system prompt，也不执行工具。
     */
    ResourceSnapshot load(Path cwd);

    /**
     * 根据资源快照构建 system prompt。
     *
     * NOTE: 构建过程只消费 ResourceSnapshot，不直接读取文件系统。
     */
    SystemPrompt buildSystemPrompt(ResourceSnapshot resources);
}
