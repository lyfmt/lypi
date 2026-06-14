package cn.lypi.contracts.runtime;

import cn.lypi.contracts.common.AbortSignal;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.model.AssistantEventStream;
import cn.lypi.contracts.tool.ToolRegistrySnapshot;
import java.util.List;

public interface AiProviderRuntimePort {
    /**
     * 发起一次模型流式调用。
     *
     * NOTE: 返回对象为单次消费流；第一次迭代时才打开 provider 连接。
     * 调用方必须关闭流对象。Provider 原始协议必须在 adapter 内转换为 AssistantStreamEvent。
     */
    AssistantEventStream stream(ContextSnapshot context, AbortSignal signal);

    /**
     * 发起一次带运行选项的模型流式调用。
     *
     * NOTE: 默认实现保持旧 provider 兼容；支持会话级调用选项的 provider 应重写该方法。
     */
    default AssistantEventStream stream(ContextSnapshot context, AiStreamOptions options, AbortSignal signal) {
        return stream(context, signal);
    }

    /**
     * 发起一次带工具注册表快照的模型流式调用。
     *
     * NOTE: 默认实现保持旧 provider 兼容；支持工具调用的 provider 应重写该方法并把工具定义传给模型。
     */
    default AssistantEventStream stream(ContextSnapshot context, ToolRegistrySnapshot tools, AbortSignal signal) {
        return stream(context, signal);
    }

    /**
     * 发起一次带工具注册表快照和运行选项的模型流式调用。
     *
     * NOTE: 默认实现保持旧 provider 兼容；支持工具调用和会话级选项的 provider 应重写该方法。
     */
    default AssistantEventStream stream(
        ContextSnapshot context,
        ToolRegistrySnapshot tools,
        AiStreamOptions options,
        AbortSignal signal
    ) {
        return stream(context, tools, signal);
    }

    /**
     * 返回空工具注册表快照。
     */
    static ToolRegistrySnapshot emptyTools() {
        return new ToolRegistrySnapshot(List.of());
    }
}
