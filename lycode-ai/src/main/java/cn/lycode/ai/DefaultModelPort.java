package cn.lycode.ai;

import cn.lycode.ai.stream.CompletedAssistantEventStream;
import cn.lycode.contracts.common.AbortSignal;
import cn.lycode.contracts.context.ContextSnapshot;
import cn.lycode.contracts.error.ErrorSeverity;
import cn.lycode.contracts.error.ModelProviderException;
import cn.lycode.contracts.model.AssistantEventStream;
import cn.lycode.contracts.model.ModelDescriptor;
import cn.lycode.contracts.model.ThinkingLevel;
import cn.lycode.contracts.runtime.AiProviderRuntimePort;
import cn.lycode.contracts.runtime.AiStreamOptions;
import cn.lycode.contracts.tool.ToolRegistrySnapshot;
import java.util.Objects;

public final class DefaultModelPort implements ModelPort {
    private final ModelRegistry registry;
    private final ApiProviderRegistry apiProviders;

    public DefaultModelPort(ModelRegistry registry, ApiProviderRegistry apiProviders) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.apiProviders = Objects.requireNonNull(apiProviders, "apiProviders");
    }

    @Override
    public AssistantEventStream stream(ContextSnapshot context, AbortSignal signal) {
        return stream(context, AiProviderRuntimePort.emptyTools(), signal);
    }

    @Override
    public AssistantEventStream stream(ContextSnapshot context, ToolRegistrySnapshot tools, AbortSignal signal) {
        return stream(context, tools, AiStreamOptions.empty(), signal);
    }

    @Override
    public AssistantEventStream stream(
        ContextSnapshot context,
        ToolRegistrySnapshot tools,
        AiStreamOptions options,
        AbortSignal signal
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(signal, "signal");
        if (signal.aborted()) {
            return CompletedAssistantEventStream.aborted();
        }

        ModelDescriptor descriptor = registry.find(context.model())
            .orElseThrow(() -> unavailable("model.unavailable", "Selected model is not available."));
        validateThinking(context, descriptor);

        ApiProvider apiProvider = apiProviders.find(descriptor.apiStyle())
            .orElseThrow(() -> unavailable("api_provider.unavailable", "API provider is not available."));

        return apiProvider.stream(context, descriptor, tools, options, signal);
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
