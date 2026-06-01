package cn.lypi.ai;

import cn.lypi.contracts.common.AbortSignal;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.model.AssistantStreamEvent;
import cn.lypi.contracts.runtime.AiProviderRuntimePort;
import java.util.stream.Stream;

public interface ModelPort extends AiProviderRuntimePort {
    /*
    * @status : 未完成
    * @summary : 发起一次模型流式调用。
    *@description : provider 原始协议必须在 adapter 内转换为 AssistantStreamEvent，不得泄漏到 agent-core。
    *
    *
                              */
    Stream<AssistantStreamEvent> stream(ContextSnapshot context, AbortSignal signal);
}

