package cn.lypi.contracts.common;

public interface ProgressSink {
    /**
     * 发布结构化工具进度。
     *
     * NOTE: 发布端会忽略 null progress；工具实现应优先使用 ToolProgress 的工厂方法构造进度。
     */
    void progress(ToolProgress progress);
}
