package cn.lypi.ai.provider.openai;

import cn.lypi.ai.ProviderAdapter;
import cn.lypi.ai.provider.ProviderFallbackDecider;
import cn.lypi.ai.provider.ProviderRawEvent;
import cn.lypi.ai.provider.ProviderRequest;
import cn.lypi.ai.provider.ProviderTransport;
import cn.lypi.ai.provider.TransportMode;
import cn.lypi.ai.spec.ContextSnapshotRequestFactory;
import cn.lypi.ai.spec.LypiModelRequest;
import cn.lypi.ai.spec.ToolSpecMapper;
import cn.lypi.contracts.common.AbortSignal;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.error.ErrorSeverity;
import cn.lypi.contracts.error.ModelProviderException;
import cn.lypi.contracts.model.AssistantError;
import cn.lypi.contracts.model.AssistantStreamEvent;
import cn.lypi.contracts.model.ModelDescriptor;
import cn.lypi.contracts.tool.ToolDescriptor;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

public final class OpenAiCompatibleProviderAdapter implements ProviderAdapter {
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
    public Stream<AssistantStreamEvent> stream(
        ContextSnapshot context,
        ModelDescriptor descriptor,
        List<ToolDescriptor> tools,
        AbortSignal signal
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(tools, "tools");
        Objects.requireNonNull(signal, "signal");
        if (config.apiKey() == null || config.apiKey().isBlank()) {
            throw new ModelProviderException(
                "provider.api_key_missing",
                ErrorSeverity.ERROR,
                false,
                "Provider API key is not configured."
            );
        }
        LypiModelRequest request = ContextSnapshotRequestFactory.from(
            context,
            UUID.randomUUID().toString(),
            ToolSpecMapper.fromDescriptors(tools)
        );
        List<Attempt> attempts = attempts(request);
        RuntimeException lastFailure = null;
        for (Attempt attempt : attempts) {
            for (int retry = 0; retry <= Math.max(0, config.maxRetries()); retry++) {
                boolean outputStarted = false;
                List<AssistantStreamEvent> events = new ArrayList<>();
                try {
                    for (ProviderRawEvent rawEvent : (Iterable<ProviderRawEvent>) attempt.transport.stream(attempt.request, signal)::iterator) {
                        List<AssistantStreamEvent> normalized = attempt.normalize(rawEvent.data());
                        if (!normalized.isEmpty()) {
                            outputStarted = true;
                        }
                        events.addAll(normalized);
                    }
                    return events.stream();
                } catch (RuntimeException error) {
                    lastFailure = error;
                    if (!fallbackDecider.shouldFallback(error, outputStarted)) {
                        throw error;
                    }
                }
            }
        }
        RuntimeException failure = lastFailure == null ? new IllegalStateException("Provider request failed.") : lastFailure;
        return Stream.of(new AssistantError("provider.request_failed", failure.getMessage()));
    }

    private List<Attempt> attempts(LypiModelRequest request) {
        List<Attempt> attempts = new ArrayList<>();
        addAttemptsForStyle(attempts, request, config.requestStyle());
        if (config.fallbackRequestStyle() != config.requestStyle()) {
            addAttemptsForStyle(attempts, request, config.fallbackRequestStyle());
        }
        return attempts;
    }

    private void addAttemptsForStyle(List<Attempt> attempts, LypiModelRequest request, cn.lypi.ai.provider.RequestStyle style) {
        if (style == cn.lypi.ai.provider.RequestStyle.RESPONSES) {
            if (config.transportMode() == TransportMode.AUTO || config.transportMode() == TransportMode.WEBSOCKET) {
                OpenAiResponsesStreamNormalizer normalizer = new OpenAiResponsesStreamNormalizer();
                attempts.add(new Attempt(webSocketTransport, responsesWebSocketRequest(request), normalizer::normalize));
            }
            if (config.transportMode() == TransportMode.AUTO || config.transportMode() == TransportMode.SSE) {
                OpenAiResponsesStreamNormalizer normalizer = new OpenAiResponsesStreamNormalizer();
                attempts.add(new Attempt(responsesSseTransport, responsesSseRequest(request), normalizer::normalize));
            }
            return;
        }
        if (config.transportMode() == TransportMode.AUTO || config.transportMode() == TransportMode.SSE) {
            OpenAiChatCompletionsStreamNormalizer normalizer = new OpenAiChatCompletionsStreamNormalizer();
            attempts.add(new Attempt(chatCompletionsSseTransport, chatCompletionsRequest(request), normalizer::normalize));
        }
    }

    private ProviderRequest responsesWebSocketRequest(LypiModelRequest request) {
        URI uri = config.websocketUrl()
            .orElseGet(() -> cn.lypi.ai.transport.WebSocketProviderTransport.deriveUri(config.baseUrl(), config.websocketPath()));
        ObjectNode body = responsesRequestBuilder.buildWebSocketCreateEvent(request, config);
        return new ProviderRequest(uri, headers(), body.toString(), java.util.Optional.of(config.timeout()));
    }

    private ProviderRequest responsesSseRequest(LypiModelRequest request) {
        ObjectNode body = responsesRequestBuilder.build(request, config);
        return new ProviderRequest(endpoint("responses"), headers(), body.toString(), java.util.Optional.of(config.timeout()));
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

    private record Attempt(
        ProviderTransport transport,
        ProviderRequest request,
        EventNormalizer normalizer
    ) {
        private List<AssistantStreamEvent> normalize(String data) {
            return normalizer.normalize(data);
        }
    }

    private interface EventNormalizer {
        List<AssistantStreamEvent> normalize(String data);
    }
}
