package cn.lycode.ai.provider.openai;

import cn.lycode.ai.ApiProvider;
import cn.lycode.ai.ProviderAdapter;
import cn.lycode.ai.provider.ProviderFallbackDecider;
import cn.lycode.ai.provider.ProviderRequest;
import cn.lycode.ai.provider.ProviderTransport;
import cn.lycode.ai.provider.TransportMode;
import cn.lycode.ai.spec.ContextSnapshotRequestFactory;
import cn.lycode.ai.spec.LyCodeModelRequest;
import cn.lycode.ai.spec.LyCodeToolSpec;
import cn.lycode.contracts.common.AbortSignal;
import cn.lycode.contracts.context.ContextSnapshot;
import cn.lycode.contracts.error.ErrorSeverity;
import cn.lycode.contracts.error.ModelProviderException;
import cn.lycode.contracts.model.AssistantEventStream;
import cn.lycode.contracts.model.ApiStyle;
import cn.lycode.contracts.model.ModelDescriptor;
import cn.lycode.contracts.runtime.AiProviderRuntimePort;
import cn.lycode.contracts.runtime.AiStreamOptions;
import cn.lycode.contracts.tool.ToolDescriptor;
import cn.lycode.contracts.tool.ToolRegistrySnapshot;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class OpenAiCompatibleProviderAdapter implements ProviderAdapter, ApiProvider {
    private final OpenAiProviderConfig config;
    private final ProviderTransport webSocketTransport;
    private final ProviderTransport responsesSseTransport;
    private final ProviderTransport chatCompletionsSseTransport;
    private final ProviderFallbackDecider fallbackDecider;
    private final OpenAiResponsesRequestBuilder responsesRequestBuilder;
    private final OpenAiChatCompletionsRequestBuilder chatCompletionsRequestBuilder;

    public OpenAiCompatibleProviderAdapter(
        OpenAiProviderConfig config,
        ProviderTransport webSocketTransport,
        ProviderTransport responsesSseTransport,
        ProviderTransport chatCompletionsSseTransport
    ) {
        this(
            config,
            webSocketTransport,
            responsesSseTransport,
            chatCompletionsSseTransport,
            new ProviderFallbackDecider(),
            new OpenAiResponsesRequestBuilder(),
            new OpenAiChatCompletionsRequestBuilder()
        );
    }

    public OpenAiCompatibleProviderAdapter(
        OpenAiProviderConfig config,
        ProviderTransport webSocketTransport,
        ProviderTransport responsesSseTransport,
        ProviderTransport chatCompletionsSseTransport,
        ProviderFallbackDecider fallbackDecider,
        OpenAiResponsesRequestBuilder responsesRequestBuilder,
        OpenAiChatCompletionsRequestBuilder chatCompletionsRequestBuilder
    ) {
        this.config = Objects.requireNonNull(config, "config");
        this.webSocketTransport = Objects.requireNonNull(webSocketTransport, "webSocketTransport");
        this.responsesSseTransport = Objects.requireNonNull(responsesSseTransport, "responsesSseTransport");
        this.chatCompletionsSseTransport = Objects.requireNonNull(chatCompletionsSseTransport, "chatCompletionsSseTransport");
        this.fallbackDecider = Objects.requireNonNull(fallbackDecider, "fallbackDecider");
        this.responsesRequestBuilder = Objects.requireNonNull(responsesRequestBuilder, "responsesRequestBuilder");
        this.chatCompletionsRequestBuilder = Objects.requireNonNull(chatCompletionsRequestBuilder, "chatCompletionsRequestBuilder");
    }

    @Override
    public String provider() {
        return config.provider();
    }

    @Override
    public ApiStyle apiStyle() {
        return ApiStyle.OPENAI_COMPATIBLE;
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
        LyCodeModelRequest request = ContextSnapshotRequestFactory.from(context, UUID.randomUUID().toString(), toolSpecs(tools));
        if (!options.sessionId().isBlank()) {
            request = requestWithPromptCacheKey(request, options.sessionId());
        }
        return new OpenAiAssistantEventStream(
            attempts(request),
            config.provider(),
            signal,
            fallbackDecider,
            config.maxRetries()
        );
    }

    private LyCodeModelRequest requestWithPromptCacheKey(LyCodeModelRequest request, String promptCacheKey) {
        Map<String, Object> metadata = new java.util.LinkedHashMap<>(request.metadata());
        metadata.put("promptCacheKey", promptCacheKey);
        return new LyCodeModelRequest(
            request.requestId(),
            request.model(),
            request.thinkingLevel(),
            request.systemPrompt(),
            request.messages(),
            request.tools(),
            request.options(),
            metadata
        );
    }

    private List<LyCodeToolSpec> toolSpecs(ToolRegistrySnapshot tools) {
        if (tools == null || tools.tools() == null || tools.tools().isEmpty()) {
            return List.of();
        }
        return tools.tools().stream()
            .map(this::toolSpec)
            .toList();
    }

    private LyCodeToolSpec toolSpec(ToolDescriptor descriptor) {
        Map<String, Object> inputSchema = descriptor.inputSchema() == null || descriptor.inputSchema().value() == null
            ? Map.of()
            : descriptor.inputSchema().value();
        return new LyCodeToolSpec(
            descriptor.name(),
            descriptor.description(),
            inputSchema
        );
    }

    private List<OpenAiStreamAttempt> attempts(LyCodeModelRequest request) {
        List<OpenAiStreamAttempt> attempts = new ArrayList<>();
        addAttemptsForStyle(attempts, request, config.requestStyle());
        if (config.fallbackRequestStyle() != config.requestStyle()) {
            addAttemptsForStyle(attempts, request, config.fallbackRequestStyle());
        }
        return attempts;
    }

    private void addAttemptsForStyle(List<OpenAiStreamAttempt> attempts, LyCodeModelRequest request, cn.lycode.ai.provider.RequestStyle style) {
        if (style == cn.lycode.ai.provider.RequestStyle.RESPONSES) {
            if (config.transportMode() == TransportMode.AUTO || config.transportMode() == TransportMode.WEBSOCKET) {
                OpenAiResponsesStreamNormalizer normalizer = new OpenAiResponsesStreamNormalizer();
                attempts.add(new OpenAiStreamAttempt(
                    "responses/websocket",
                    webSocketTransport,
                    responsesWebSocketRequest(request),
                    normalizer
                ));
            }
            if (config.transportMode() == TransportMode.AUTO || config.transportMode() == TransportMode.SSE) {
                OpenAiResponsesStreamNormalizer normalizer = new OpenAiResponsesStreamNormalizer();
                attempts.add(new OpenAiStreamAttempt(
                    "responses/sse",
                    responsesSseTransport,
                    responsesSseRequest(request, OpenAiResponsesRequestOptions.fallbackWithoutPreviousResponseState()),
                    normalizer
                ));
            }
            return;
        }
        if (config.transportMode() == TransportMode.AUTO || config.transportMode() == TransportMode.SSE) {
            addChatCompletionsSseAttempt(attempts, request);
        }
    }

    private void addChatCompletionsSseAttempt(List<OpenAiStreamAttempt> attempts, LyCodeModelRequest request) {
        OpenAiChatCompletionsStreamNormalizer normalizer = new OpenAiChatCompletionsStreamNormalizer();
        attempts.add(new OpenAiStreamAttempt(
            "chat_completions/sse",
            chatCompletionsSseTransport,
            chatCompletionsRequest(request),
            normalizer
        ));
    }

    private ProviderRequest responsesWebSocketRequest(LyCodeModelRequest request) {
        URI uri = config.websocketUrl()
            .orElseGet(() -> cn.lycode.ai.transport.WebSocketProviderTransport.deriveUri(config.baseUrl(), config.websocketPath()));
        ObjectNode body = responsesRequestBuilder.buildWebSocketCreateEvent(request, config);
        return new ProviderRequest(uri, headers(), body.toString(), java.util.Optional.of(config.timeout()));
    }

    private ProviderRequest responsesSseRequest(LyCodeModelRequest request, OpenAiResponsesRequestOptions options) {
        ObjectNode body = responsesRequestBuilder.build(request, config, options);
        return new ProviderRequest(endpoint("responses"), headers(), body.toString(), java.util.Optional.of(config.timeout()));
    }

    private ProviderRequest chatCompletionsRequest(LyCodeModelRequest request) {
        ObjectNode body = chatCompletionsRequestBuilder.build(request, config);
        return new ProviderRequest(endpoint("chat/completions"), headers(), body.toString(), java.util.Optional.of(config.timeout()));
    }

    private Map<String, String> headers() {
        return Map.of("Authorization", "Bearer " + config.apiKey());
    }

    private URI endpoint(String suffix) {
        String base = config.baseUrl().toString();
        String normalizedBase = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        String normalizedSuffix = suffix.startsWith("/") ? suffix.substring(1) : suffix;
        return URI.create(normalizedBase + "/" + normalizedSuffix);
    }

}
