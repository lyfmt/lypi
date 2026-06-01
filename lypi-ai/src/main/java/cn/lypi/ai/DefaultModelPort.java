package cn.lypi.ai;

import cn.lypi.contracts.common.AbortSignal;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.error.ErrorSeverity;
import cn.lypi.contracts.error.ModelProviderException;
import cn.lypi.contracts.model.AssistantStreamEvent;
import cn.lypi.contracts.model.ModelDescriptor;
import cn.lypi.contracts.model.ThinkingLevel;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class DefaultModelPort implements ModelPort {
    private final ModelRegistry registry;
    private final Map<String, ProviderAdapter> adapters;

    public DefaultModelPort(ModelRegistry registry, List<ProviderAdapter> adapters) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.adapters = List.copyOf(Objects.requireNonNull(adapters, "adapters")).stream()
            .collect(Collectors.toUnmodifiableMap(ProviderAdapter::provider, Function.identity()));
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

        ProviderAdapter adapter = adapters.get(descriptor.provider());
        if (adapter == null) {
            throw unavailable("provider.adapter_unavailable", "Provider adapter is not available.");
        }

        return adapter.stream(context, descriptor, signal);
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
