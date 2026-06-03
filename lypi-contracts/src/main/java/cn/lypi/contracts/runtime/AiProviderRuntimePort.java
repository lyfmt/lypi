package cn.lypi.contracts.runtime;

import cn.lypi.contracts.common.AbortSignal;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.model.AssistantStreamEvent;
import cn.lypi.contracts.tool.ToolDescriptor;
import java.util.List;
import java.util.stream.Stream;

public interface AiProviderRuntimePort {
    /**
     * 发起一次模型流式调用。
     *
     * NOTE: Provider 原始协议必须在 adapter 内转换为 AssistantStreamEvent，不得泄漏到 agent-core。
     */
    default Stream<AssistantStreamEvent> stream(ContextSnapshot context, AbortSignal signal) {
        return stream(context, List.of(), signal);
    }

    /**
     * 发起一次带工具规格的模型流式调用。
     *
     * NOTE: 工具规格来自 ToolRuntimePort 快照，AI 层不得直接依赖工具实现对象。
     */
    Stream<AssistantStreamEvent> stream(ContextSnapshot context, List<ToolDescriptor> tools, AbortSignal signal);
}
