package cn.lypi.ai;

import cn.lypi.contracts.common.AbortSignal;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.error.ErrorSeverity;
import cn.lypi.contracts.error.ModelProviderException;
import cn.lypi.contracts.model.ApiStyle;
import cn.lypi.contracts.model.AssistantEventStream;
import cn.lypi.contracts.model.ModelDescriptor;
import cn.lypi.contracts.runtime.AiProviderRuntimePort;
import cn.lypi.contracts.runtime.AiStreamOptions;
import cn.lypi.contracts.tool.ToolRegistrySnapshot;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class ProviderAdapterApiProvider implements ApiProvider {
    private final ApiStyle apiStyle;
    private final AtomicReference<Map<String, ProviderAdapter>> adapters;

    public ProviderAdapterApiProvider(ApiStyle apiStyle, List<? extends ProviderAdapter> adapters) {
        this.apiStyle = Objects.requireNonNull(apiStyle, "apiStyle");
        this.adapters = new AtomicReference<>(indexAdapters(adapters));
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
        return stream(context, descriptor, tools, AiStreamOptions.empty(), signal);
    }

    @Override
    public AssistantEventStream stream(
        ContextSnapshot context,
        ModelDescriptor descriptor,
        ToolRegistrySnapshot tools,
        AiStreamOptions options,
        AbortSignal signal
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(signal, "signal");
        ProviderAdapter adapter = adapters.get().get(descriptor.provider());
        if (adapter == null) {
            throw new ModelProviderException(
                "provider.adapter_unavailable",
                ErrorSeverity.ERROR,
                false,
                "Provider adapter is not available."
            );
        }
        return adapter.stream(context, descriptor, tools, options, signal);
    }

    public void replaceAdapter(ProviderAdapter adapter) {
        ProviderAdapter requiredAdapter = Objects.requireNonNull(adapter, "adapter");
        adapters.updateAndGet(current -> {
            Map<String, ProviderAdapter> next = new LinkedHashMap<>(current);
            next.put(requiredAdapter.provider(), requiredAdapter);
            return Map.copyOf(next);
        });
    }

    private static Map<String, ProviderAdapter> indexAdapters(List<? extends ProviderAdapter> adapters) {
        Map<String, ProviderAdapter> indexed = new LinkedHashMap<>();
        for (ProviderAdapter adapter : List.copyOf(Objects.requireNonNull(adapters, "adapters"))) {
            ProviderAdapter previous = indexed.putIfAbsent(adapter.provider(), adapter);
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate provider adapter: " + adapter.provider());
            }
        }
        return Map.copyOf(indexed);
    }
}
