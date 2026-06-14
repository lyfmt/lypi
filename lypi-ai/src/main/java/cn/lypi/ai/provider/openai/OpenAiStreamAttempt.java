package cn.lypi.ai.provider.openai;

import cn.lypi.ai.provider.ProviderRequest;
import cn.lypi.ai.provider.ProviderTransport;
import cn.lypi.contracts.model.AssistantStreamEvent;
import java.util.List;
import java.util.function.Consumer;

record OpenAiStreamAttempt(
    ProviderTransport transport,
    ProviderRequest request,
    OpenAiStreamNormalizer normalizer,
    Consumer<RuntimeException> failureObserver
) {
    OpenAiStreamAttempt(
        ProviderTransport transport,
        ProviderRequest request,
        OpenAiStreamNormalizer normalizer
    ) {
        this(transport, request, normalizer, ignored -> {
        });
    }

    List<AssistantStreamEvent> normalize(String data) {
        return normalizer.normalize(data);
    }

    void observeFailure(RuntimeException exception) {
        failureObserver.accept(exception);
    }
}
