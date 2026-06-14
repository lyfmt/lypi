package cn.lypi.ai.provider.openai;

import cn.lypi.contracts.model.AssistantStreamEvent;
import cn.lypi.contracts.model.ProviderConversationState;
import java.util.List;
import java.util.Optional;

interface OpenAiStreamNormalizer {
    List<AssistantStreamEvent> normalize(String data);

    default Optional<ProviderConversationState> providerConversationState() {
        return Optional.empty();
    }
}
