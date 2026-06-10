package cn.lypi.ai;

import cn.lypi.contracts.common.AbortSignal;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.model.ApiStyle;
import cn.lypi.contracts.model.AssistantEventStream;
import cn.lypi.contracts.model.ModelDescriptor;
import cn.lypi.contracts.tool.ToolRegistrySnapshot;

public interface ApiProvider {
    /**
     * 返回该 provider 支持的 API 风格。
     *
     * NOTE: 多个外部 provider 可以共享同一个 API provider，例如 OpenAI-compatible。
     */
    ApiStyle apiStyle();

     /**
     * 发起 provider 流式调用并标准化输出。
     */
    AssistantEventStream stream(ContextSnapshot context, ModelDescriptor descriptor, AbortSignal signal);

    /**
     * 发起带工具注册表快照的 provider 流式调用并标准化输出。
     */
    default AssistantEventStream stream(
        ContextSnapshot context,
        ModelDescriptor descriptor,
        ToolRegistrySnapshot tools,
        AbortSignal signal
    ) {
        return stream(context, descriptor, signal);
    }
}
