package cn.lypi.agent;

public interface ContextAssembler {
    /**
     * 构建模型上下文快照。
     *
     * NOTE: 从 session 分支路径恢复消息、模式、权限、压缩摘要和预算视图，不改写 transcript。
     */
    ContextAssembly build(ContextBuildRequest request);
}
