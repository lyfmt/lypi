package cn.lycode.ai.provider.openai;

import cn.lycode.contracts.model.AssistantStreamEvent;
import cn.lycode.contracts.model.ProviderConversationState;
import java.util.List;
import java.util.Optional;

interface OpenAiStreamNormalizer {
    List<AssistantStreamEvent> normalize(String data);

    default Optional<ProviderConversationState> providerConversationState() {
        return Optional.empty();
    }
}
