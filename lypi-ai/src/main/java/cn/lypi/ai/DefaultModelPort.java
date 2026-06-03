package cn.lypi.ai;

import cn.lypi.contracts.common.AbortSignal;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.error.ErrorSeverity;
import cn.lypi.contracts.error.ModelProviderException;
import cn.lypi.contracts.model.AssistantStreamEvent;
import cn.lypi.contracts.model.ModelDescriptor;
import cn.lypi.contracts.model.ThinkingLevel;
import java.util.Objects;
import java.util.stream.Stream;

public final class DefaultModelPort implements ModelPort {
    private final ModelRegistry registry;
    private final ApiProviderRegistry apiProviders;

    public DefaultModelPort(ModelRegistry registry, ApiProviderRegistry apiProviders) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.apiProviders = Objects.requireNonNull(apiProviders, "apiProviders");
    }

    @Override
    public Stream<AssistantStreamEvent> stream(ContextSnapshot context, AbortSignal signal) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(signal, "signal");
        if (signal.aborted()) {
            return Stream.empty();
        }

        ModelDescriptor descriptor = registry.find(context.model())
            .orElseThrow(() -> unavailable("model.unavailable", "Selected model is not available."));
        validateThinking(context, descriptor);

        ApiProvider apiProvider = apiProviders.find(descriptor.apiStyle())
            .orElseThrow(() -> unavailable("api_provider.unavailable", "API provider is not available."));

        return apiProvider.stream(context, descriptor, signal);
    }

    private static void validateThinking(ContextSnapshot context, ModelDescriptor descriptor) {
        if (!descriptor.supportsThinking() && context.thinkingLevel() != ThinkingLevel.OFF) {
            throw unavailable("model.thinking_unsupported", "Selected model does not support thinking.");
        }
    }

    private static ModelProviderException unavailable(String errorId, String message) {
        return new ModelProviderException(errorId, ErrorSeverity.ERROR, false, message);
    }
}
