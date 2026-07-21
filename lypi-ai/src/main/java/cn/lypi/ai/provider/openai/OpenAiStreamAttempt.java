package cn.lypi.ai.provider.openai;

import cn.lypi.ai.provider.ProviderRequest;
import cn.lypi.ai.provider.ProviderTransport;
import cn.lypi.contracts.model.AssistantStreamEvent;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

record OpenAiStreamAttempt(
    String mode,
    ProviderTransport transport,
    ProviderRequest request,
    OpenAiStreamNormalizer normalizer,
    Consumer<RuntimeException> failureObserver
) {
    OpenAiStreamAttempt {
        mode = Objects.requireNonNull(mode, "mode");
        if (mode.isBlank()) {
            throw new IllegalArgumentException("mode must not be blank");
        }
    }

    OpenAiStreamAttempt(
        String mode,
        ProviderTransport transport,
        ProviderRequest request,
        OpenAiStreamNormalizer normalizer
    ) {
        this(mode, transport, request, normalizer, ignored -> {
        });
    }

    List<AssistantStreamEvent> normalize(String data) {
        return normalizer.normalize(data);
    }

    void observeFailure(RuntimeException exception) {
        failureObserver.accept(exception);
    }
}
