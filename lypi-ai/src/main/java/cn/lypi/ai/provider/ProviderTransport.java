package cn.lypi.ai.provider;

import cn.lypi.contracts.common.AbortSignal;
import java.util.stream.Stream;

public interface ProviderTransport {
    /**
     * 发起 provider 请求并返回原始 stream data。
     *
     * NOTE: 原始事件只能由 provider adapter 消费，不得直接暴露给 agent-core。
     */
    Stream<ProviderRawEvent> stream(ProviderRequest request, AbortSignal signal);
}
