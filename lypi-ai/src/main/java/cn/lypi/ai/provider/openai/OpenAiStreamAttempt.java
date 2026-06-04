package cn.lypi.ai.provider.openai;

import cn.lypi.ai.provider.ProviderRequest;
import cn.lypi.ai.provider.ProviderTransport;
import cn.lypi.contracts.model.AssistantStreamEvent;
import java.util.List;

record OpenAiStreamAttempt(
    ProviderTransport transport,
    ProviderRequest request,
    OpenAiStreamNormalizer normalizer
) {
    List<AssistantStreamEvent> normalize(String data) {
        return normalizer.normalize(data);
    }
}
