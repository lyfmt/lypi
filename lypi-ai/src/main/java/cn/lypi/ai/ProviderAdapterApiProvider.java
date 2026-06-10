package cn.lypi.ai;

import cn.lypi.contracts.common.AbortSignal;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.error.ErrorSeverity;
import cn.lypi.contracts.error.ModelProviderException;
import cn.lypi.contracts.model.ApiStyle;
import cn.lypi.contracts.model.AssistantEventStream;
import cn.lypi.contracts.model.ModelDescriptor;
import cn.lypi.contracts.runtime.AiProviderRuntimePort;
import cn.lypi.contracts.tool.ToolRegistrySnapshot;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class ProviderAdapterApiProvider implements ApiProvider {
    private final ApiStyle apiStyle;
    private final Map<String, ProviderAdapter> adapters;

    public ProviderAdapterApiProvider(ApiStyle apiStyle, List<? extends ProviderAdapter> adapters) {
        this.apiStyle = Objects.requireNonNull(apiStyle, "apiStyle");
        this.adapters = List.copyOf(Objects.requireNonNull(adapters, "adapters")).stream()
            .collect(Collectors.toUnmodifiableMap(ProviderAdapter::provider, Function.identity()));
    }

    @Override
    public ApiStyle apiStyle() {
        return apiStyle;
    }

    @Override
    public AssistantEventStream stream(ContextSnapshot context, ModelDescriptor descriptor, AbortSignal signal) {
        return stream(context, descriptor, AiProviderRuntimePort.emptyTools(), signal);
    }

    @Override
    public AssistantEventStream stream(
        ContextSnapshot context,
        ModelDescriptor descriptor,
        ToolRegistrySnapshot tools,
        AbortSignal signal
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(signal, "signal");
        ProviderAdapter adapter = adapters.get(descriptor.provider());
        if (adapter == null) {
            throw new ModelProviderException(
                "provider.adapter_unavailable",
                ErrorSeverity.ERROR,
                false,
                "Provider adapter is not available."
            );
        }
        return adapter.stream(context, descriptor, tools, signal);
    }
}
