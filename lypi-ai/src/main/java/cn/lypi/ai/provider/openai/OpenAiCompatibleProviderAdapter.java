package cn.lypi.ai.provider.openai;

import cn.lypi.ai.ApiProvider;
import cn.lypi.ai.ProviderAdapter;
import cn.lypi.ai.provider.ProviderFallbackDecider;
import cn.lypi.ai.provider.ProviderRequest;
import cn.lypi.ai.provider.ProviderTransport;
import cn.lypi.ai.provider.TransportMode;
import cn.lypi.ai.spec.ContextSnapshotRequestFactory;
import cn.lypi.ai.spec.LypiModelRequest;
import cn.lypi.ai.spec.LypiToolSpec;
import cn.lypi.contracts.common.AbortSignal;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.error.ErrorSeverity;
import cn.lypi.contracts.error.ModelProviderException;
import cn.lypi.contracts.model.AssistantEventStream;
import cn.lypi.contracts.model.ApiStyle;
import cn.lypi.contracts.model.ModelDescriptor;
import cn.lypi.contracts.runtime.AiProviderRuntimePort;
import cn.lypi.contracts.runtime.AiStreamOptions;
import cn.lypi.contracts.tool.ToolDescriptor;
import cn.lypi.contracts.tool.ToolRegistrySnapshot;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public final class OpenAiCompatibleProviderAdapter implements ProviderAdapter, ApiProvider {
    private final OpenAiProviderConfig config;
    private final ProviderTransport webSocketTransport;
    private final ProviderTransport responsesSseTransport;
    private final ProviderTransport chatCompletionsSseTransport;
    private final ProviderFallbackDecider fallbackDecider;
    private final OpenAiResponsesRequestBuilder responsesRequestBuilder;
    private final OpenAiChatCompletionsRequestBuilder chatCompletionsRequestBuilder;
    private final AtomicBoolean responsesSsePreviousStateEnabled = new AtomicBoolean(true);

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
        LypiModelRequest request = ContextSnapshotRequestFactory.from(context, UUID.randomUUID().toString(), toolSpecs(tools));
        if (!options.sessionId().isBlank()) {
            request = requestWithPromptCacheKey(request, options.sessionId());
        }
        return new OpenAiAssistantEventStream(attempts(request), signal, fallbackDecider, config.maxRetries());
    }

    private LypiModelRequest requestWithPromptCacheKey(LypiModelRequest request, String promptCacheKey) {
        Map<String, Object> metadata = new java.util.LinkedHashMap<>(request.metadata());
        metadata.put("promptCacheKey", promptCacheKey);
        return new LypiModelRequest(
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

    private List<OpenAiStreamAttempt> attempts(LypiModelRequest request) {
        List<OpenAiStreamAttempt> attempts = new ArrayList<>();
        addAttemptsForStyle(attempts, request, config.requestStyle());
        if (config.fallbackRequestStyle() != config.requestStyle()) {
            addAttemptsForStyle(attempts, request, config.fallbackRequestStyle());
        }
        return attempts;
    }

    private void addAttemptsForStyle(List<OpenAiStreamAttempt> attempts, LypiModelRequest request, cn.lypi.ai.provider.RequestStyle style) {
        if (style == cn.lypi.ai.provider.RequestStyle.RESPONSES) {
            if (config.transportMode() == TransportMode.AUTO || config.transportMode() == TransportMode.WEBSOCKET) {
                OpenAiResponsesStreamNormalizer normalizer = new OpenAiResponsesStreamNormalizer();
                attempts.add(new OpenAiStreamAttempt(webSocketTransport, responsesWebSocketRequest(request), normalizer));
            }
            if (config.transportMode() == TransportMode.AUTO || config.transportMode() == TransportMode.SSE) {
                OpenAiResponsesStreamNormalizer normalizer = new OpenAiResponsesStreamNormalizer();
                attempts.add(new OpenAiStreamAttempt(
                    responsesSseTransport,
                    responsesSseRequest(
                        request,
                        OpenAiResponsesRequestOptions.withPreviousResponseState(responsesSsePreviousStateEnabled.get())
                    ),
                    normalizer,
                    this::observeResponsesSseFailure
                ));
                if (
                    responsesSsePreviousStateEnabled.get()
                        && previousResponseStateCandidate(request)
                ) {
                    OpenAiResponsesStreamNormalizer fallbackNormalizer = new OpenAiResponsesStreamNormalizer();
                    attempts.add(new OpenAiStreamAttempt(
                        responsesSseTransport,
                        responsesSseRequest(request, OpenAiResponsesRequestOptions.fallbackWithoutPreviousResponseState()),
                        fallbackNormalizer
                    ));
                }
            }
            return;
        }
        if (config.transportMode() == TransportMode.AUTO || config.transportMode() == TransportMode.SSE) {
            addChatCompletionsSseAttempt(attempts, request);
        }
    }

    private void addChatCompletionsSseAttempt(List<OpenAiStreamAttempt> attempts, LypiModelRequest request) {
        OpenAiChatCompletionsStreamNormalizer normalizer = new OpenAiChatCompletionsStreamNormalizer();
        attempts.add(new OpenAiStreamAttempt(chatCompletionsSseTransport, chatCompletionsRequest(request), normalizer));
    }

    private ProviderRequest responsesWebSocketRequest(LypiModelRequest request) {
        URI uri = config.websocketUrl()
            .orElseGet(() -> cn.lypi.ai.transport.WebSocketProviderTransport.deriveUri(config.baseUrl(), config.websocketPath()));
        ObjectNode body = responsesRequestBuilder.buildWebSocketCreateEvent(request, config);
        return new ProviderRequest(uri, headers(), body.toString(), java.util.Optional.of(config.timeout()));
    }

    private ProviderRequest responsesSseRequest(LypiModelRequest request, OpenAiResponsesRequestOptions options) {
        ObjectNode body = responsesRequestBuilder.build(request, config, options);
        return new ProviderRequest(endpoint("responses"), headers(), body.toString(), java.util.Optional.of(config.timeout()));
    }

    private boolean previousResponseStateCandidate(LypiModelRequest request) {
        Object state = request.metadata().get("providerConversationState");
        if (!(state instanceof Map<?, ?> stateMap)) {
            return false;
        }
        if (!"openai".equals(String.valueOf(stateMap.get("provider")))) {
            return false;
        }
        if (!"responses".equals(String.valueOf(stateMap.get("style")))) {
            return false;
        }
        String previousResponseId = String.valueOf(stateMap.get("previousResponseId"));
        return !previousResponseId.isBlank() && !"null".equals(previousResponseId);
    }

    private void observeResponsesSseFailure(RuntimeException exception) {
        String message = exception.getMessage() == null ? "" : exception.getMessage().toLowerCase(Locale.ROOT);
        if (message.contains("previous_response_id") && message.contains("responses websocket v2")) {
            responsesSsePreviousStateEnabled.set(false);
        }
    }

    private ProviderRequest chatCompletionsRequest(LypiModelRequest request) {
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
