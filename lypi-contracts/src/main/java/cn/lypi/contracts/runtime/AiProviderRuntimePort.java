package cn.lypi.contracts.runtime;

import cn.lypi.contracts.common.AbortSignal;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.model.AssistantEventStream;

public interface AiProviderRuntimePort {
    /**
     * 发起一次模型流式调用。
     *
     * NOTE: 返回对象为单次消费流；第一次迭代时才打开 provider 连接。
     * 调用方必须关闭流对象。Provider 原始协议必须在 adapter 内转换为 AssistantStreamEvent。
     */
    AssistantEventStream stream(ContextSnapshot context, AbortSignal signal);
}
