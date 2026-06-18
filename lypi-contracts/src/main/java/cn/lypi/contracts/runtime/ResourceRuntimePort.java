package cn.lypi.contracts.runtime;

import cn.lypi.contracts.prompt.PromptRenderRequest;
import cn.lypi.contracts.prompt.PromptRenderResult;
import cn.lypi.contracts.prompt.PromptTemplate;
import cn.lypi.contracts.prompt.SystemPrompt;
import cn.lypi.contracts.resource.ResourceSnapshot;
import cn.lypi.contracts.security.PermissionRuntimeState;
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

    /**
     * 根据资源快照和 canonical 权限运行态构建 system prompt。
     *
     * NOTE: 默认实现保留旧适配器兼容；新实现应优先消费 permissionRuntimeState。
     */
    default SystemPrompt buildSystemPrompt(ResourceSnapshot resources, PermissionRuntimeState permissionRuntimeState) {
        return buildSystemPrompt(resources);
    }

    /**
     * 渲染 Prompt Template。
     *
     * NOTE: 渲染只做参数替换和诊断，不读取额外文件、不执行工具、不授予权限。
     */
    default PromptRenderResult renderPrompt(PromptTemplate template, PromptRenderRequest request) {
        throw new UnsupportedOperationException("prompt rendering is unavailable");
    }
}
