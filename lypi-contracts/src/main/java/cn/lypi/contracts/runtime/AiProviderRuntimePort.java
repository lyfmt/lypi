package cn.lypi.contracts.runtime;

import cn.lypi.contracts.common.AbortSignal;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.model.AssistantStreamEvent;
import java.util.stream.Stream;

public interface AiProviderRuntimePort {
    /**
     * TODO: 发起一次模型流式调用。
     *
     * Provider 原始协议必须在 adapter 内转换为 AssistantStreamEvent，不得泄漏到 agent-core。
     */
    Stream<AssistantStreamEvent> stream(ContextSnapshot context, AbortSignal signal);
}
