package cn.lypi.agent;

public interface ContextAssembler {
    /**
     * 构建模型上下文快照。
     *
     * NOTE: session replay 由 SessionManager 完成；这里只拼装 system prompt 和预算视图。
     */
    ContextAssembly build(ContextBuildRequest request);
}
