package cn.lypi.ai.provider.anthropic;

import cn.lypi.ai.ApiProvider;
import cn.lypi.ai.ProviderAdapter;
import cn.lypi.ai.provider.ProviderRequest;
import cn.lypi.ai.provider.ProviderTransport;
import cn.lypi.ai.spec.ContextSnapshotRequestFactory;
import cn.lypi.ai.spec.LypiModelRequest;
import cn.lypi.ai.spec.LypiToolSpec;
import cn.lypi.contracts.common.AbortSignal;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.error.ErrorSeverity;
import cn.lypi.contracts.error.ModelProviderException;
import cn.lypi.contracts.model.ApiStyle;
import cn.lypi.contracts.model.AssistantEventStream;
import cn.lypi.contracts.model.ModelDescriptor;
import cn.lypi.contracts.runtime.AiProviderRuntimePort;
import cn.lypi.contracts.runtime.AiStreamOptions;
import cn.lypi.contracts.tool.ToolDescriptor;
import cn.lypi.contracts.tool.ToolRegistrySnapshot;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class AnthropicCompatibleProviderAdapter implements ProviderAdapter, ApiProvider {
    private final AnthropicProviderConfig config;
    private final ProviderTransport sseTransport;
    private final AnthropicMessagesRequestBuilder requestBuilder;

    public AnthropicCompatibleProviderAdapter(AnthropicProviderConfig config, ProviderTransport sseTransport) {
        this(config, sseTransport, new AnthropicMessagesRequestBuilder());
    }

    public AnthropicCompatibleProviderAdapter(
        AnthropicProviderConfig config,
        ProviderTransport sseTransport,
        AnthropicMessagesRequestBuilder requestBuilder
    ) {
        this.config = Objects.requireNonNull(config, "config");
        this.sseTransport = Objects.requireNonNull(sseTransport, "sseTransport");
        this.requestBuilder = Objects.requireNonNull(requestBuilder, "requestBuilder");
    }

    @Override
    public String provider() {
        return config.provider();
    }

    @Override
    public ApiStyle apiStyle() {
        return ApiStyle.ANTHROPIC;
    }

    @Override
    public AssistantEventStream stream(ContextSnapshot context, ModelDescriptor descriptor, AbortSignal signal) {
        return stream(context, descriptor, AiProviderRuntimePort.emptyTools(), signal);
    }

    @Override
    public AssistantEventStream stream(
        ContextSnapshot context,
        ModelDescriptor descriptor,
        AiStreamOptions options,
        AbortSignal signal
    ) {
        return stream(context, descriptor, AiProviderRuntimePort.emptyTools(), options, signal);
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
        if (config.apiKey() == null || config.apiKey().isBlank()) {
            throw new ModelProviderException(
                "provider.api_key_missing",
                ErrorSeverity.ERROR,
                false,
                "Provider API key is not configured."
            );
        }
        LypiModelRequest request = ContextSnapshotRequestFactory.from(context, UUID.randomUUID().toString(), toolSpecs(tools));
        ObjectNode body = requestBuilder.build(request, config);
        return new AnthropicAssistantEventStream(
            sseTransport,
            new ProviderRequest(endpoint("messages"), headers(), body.toString(), Optional.of(config.timeout())),
            signal,
            config.maxRetries()
        );
    }

    private List<LypiToolSpec> toolSpecs(ToolRegistrySnapshot tools) {
        if (tools == null || tools.tools() == null || tools.tools().isEmpty()) {
            return List.of();
        }
        return tools.tools().stream()
            .map(this::toolSpec)
            .toList();
    }

    private LypiToolSpec toolSpec(ToolDescriptor descriptor) {
        Map<String, Object> inputSchema = descriptor.inputSchema() == null || descriptor.inputSchema().value() == null
            ? Map.of()
            : descriptor.inputSchema().value();
        return new LypiToolSpec(
            descriptor.name(),
            descriptor.description(),
            inputSchema
        );
    }

    private Map<String, String> headers() {
        return Map.of(
            "x-api-key", config.apiKey(),
            "anthropic-version", config.anthropicVersion()
        );
    }

    private URI endpoint(String suffix) {
        String base = config.baseUrl().toString();
        String normalizedBase = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        String normalizedSuffix = suffix.startsWith("/") ? suffix.substring(1) : suffix;
        return URI.create(normalizedBase + "/" + normalizedSuffix);
    }
}
