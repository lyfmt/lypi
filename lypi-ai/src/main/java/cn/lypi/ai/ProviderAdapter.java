package cn.lypi.ai;

import cn.lypi.contracts.common.AbortSignal;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.model.AssistantStreamEvent;
import cn.lypi.contracts.model.ModelDescriptor;
import cn.lypi.contracts.tool.ToolDescriptor;
import java.util.List;
import java.util.stream.Stream;

public interface ProviderAdapter {
    /**
     * 返回该 adapter 负责的 provider 名称。
     *
     * NOTE: 名称必须与 ModelDescriptor.provider 保持一致，用于 ModelPort 路由模型调用。
     */
    String provider();

    /**
     * 发起 provider 流式调用并标准化输出。
     *
     * NOTE: 具体 provider 协议、thinking 参数和原始 SSE 必须在 adapter 内转换，不得泄漏到上层。
     */
    default Stream<AssistantStreamEvent> stream(ContextSnapshot context, ModelDescriptor descriptor, AbortSignal signal) {
        return stream(context, descriptor, List.of(), signal);
    }

    /**
     * 发起带工具规格的 provider 流式调用并标准化输出。
     *
     * NOTE: 工具规格必须在 adapter 内转换为具体 provider schema，不得泄漏 provider 字段到上层。
     */
    Stream<AssistantStreamEvent> stream(
        ContextSnapshot context,
        ModelDescriptor descriptor,
        List<ToolDescriptor> tools,
        AbortSignal signal
    );
}
