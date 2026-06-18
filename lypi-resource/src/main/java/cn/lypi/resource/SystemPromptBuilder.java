package cn.lypi.resource;

import cn.lypi.contracts.prompt.SystemPrompt;
import cn.lypi.contracts.resource.ResourceSnapshot;
import cn.lypi.contracts.security.PermissionRuntimeState;

/**
 * 将资源快照转换为模型可消费的 system prompt。
 */
public interface SystemPromptBuilder {
    /**
     * 根据资源快照构建 system prompt。
     *
     * NOTE: 构建过程只消费 ResourceSnapshot，不直接读取文件系统。
     */
    SystemPrompt build(ResourceSnapshot resources);

    /**
     * 根据资源快照和 canonical 权限运行态构建 system prompt。
     *
     * NOTE: 新权限说明只消费 PermissionRuntimeState，不直接解释 boot 配置或旧 PermissionMode。
     */
    default SystemPrompt build(ResourceSnapshot resources, PermissionRuntimeState permissionRuntimeState) {
        return build(resources);
    }
}
