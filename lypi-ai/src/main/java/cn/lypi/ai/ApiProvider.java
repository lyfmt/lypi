package cn.lypi.ai;

import cn.lypi.contracts.common.AbortSignal;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.model.ApiStyle;
import cn.lypi.contracts.model.AssistantStreamEvent;
import cn.lypi.contracts.model.ModelDescriptor;
import cn.lypi.contracts.tool.ToolDescriptor;
import java.util.List;
import java.util.stream.Stream;

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
    default Stream<AssistantStreamEvent> stream(ContextSnapshot context, ModelDescriptor descriptor, AbortSignal signal) {
        return stream(context, descriptor, List.of(), signal);
    }

    /**
     * 发起带工具规格的 provider 流式调用并标准化输出。
     */
    Stream<AssistantStreamEvent> stream(
        ContextSnapshot context,
        ModelDescriptor descriptor,
        List<ToolDescriptor> tools,
        AbortSignal signal
    );
}
