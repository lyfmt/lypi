package cn.lycode.boot.ai;

import cn.lycode.ai.ProviderAdapterApiProvider;
import cn.lycode.ai.RuntimeModelRegistry;
import cn.lycode.ai.model.DiscoveredModel;
import cn.lycode.ai.model.DiscoveredModelDefaults;
import cn.lycode.ai.model.DiscoveredModelDescriptorMapper;
import cn.lycode.ai.model.RemoteModelDiscoveryClient;
import cn.lycode.ai.provider.RequestStyle;
import cn.lycode.ai.provider.TransportMode;
import cn.lycode.ai.provider.openai.OpenAiCompatibleProviderAdapter;
import cn.lycode.ai.provider.openai.OpenAiProviderConfig;
import cn.lycode.ai.transport.HttpSseProviderTransport;
import cn.lycode.ai.transport.WebSocketProviderTransport;
import cn.lycode.contracts.error.ErrorSeverity;
import cn.lycode.contracts.error.ModelProviderException;
import cn.lycode.contracts.model.ApiStyle;
import cn.lycode.contracts.model.ModelDescriptor;
import cn.lycode.contracts.runtime.ProviderLoginPort;
import cn.lycode.contracts.runtime.ProviderLoginResult;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** Registers a verified OpenAI-compatible Chat Completions provider for the current process. */
public final class OpenAiCompatibleProviderLoginService implements ProviderLoginPort {
    private static final Pattern CHANNEL_NAME = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private static final List<String> DISCOVERY_PATHS = List.of("/models", "/model");
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_RETRIES = 3;

    private final RemoteModelDiscoveryClient discoveryClient;
    private final RuntimeModelRegistry modelRegistry;
    private final ProviderAdapterApiProvider openAiDispatcher;
    private final LoginProviderPropertiesStore propertiesStore;
    private final DiscoveredModelDefaults defaults;

    public OpenAiCompatibleProviderLoginService(
        RemoteModelDiscoveryClient discoveryClient,
        RuntimeModelRegistry modelRegistry,
        ProviderAdapterApiProvider openAiDispatcher,
        LoginProviderPropertiesStore propertiesStore,
        DiscoveredModelDefaults defaults
    ) {
        this.discoveryClient = Objects.requireNonNull(discoveryClient, "discoveryClient");
        this.modelRegistry = Objects.requireNonNull(modelRegistry, "modelRegistry");
        this.openAiDispatcher = Objects.requireNonNull(openAiDispatcher, "openAiDispatcher");
        this.propertiesStore = Objects.requireNonNull(propertiesStore, "propertiesStore");
        this.defaults = Objects.requireNonNull(defaults, "defaults");
    }

    @Override
    public ProviderLoginResult register(String channelName, String rawBaseUrl, String authKey) {
        String provider = requireChannelName(channelName);
        URI baseUrl = normalizeBaseUrl(rawBaseUrl);
        String requiredAuthKey = requireAuthKey(authKey);
        List<DiscoveredModel> discovered = discoverModels(baseUrl, requiredAuthKey);
        DiscoveredModelDescriptorMapper mapper = new DiscoveredModelDescriptorMapper(
            provider,
            baseUrl,
            ApiStyle.OPENAI_COMPATIBLE,
            defaults
        );
        List<ModelDescriptor> descriptors = discovered.stream().map(mapper::map).toList();
        OpenAiCompatibleProviderAdapter adapter = chatCompletionsAdapter(provider, baseUrl, requiredAuthKey);

        try {
            propertiesStore.save(provider, baseUrl, requiredAuthKey);
        } catch (IOException | RuntimeException error) {
            throw providerLoginFailure(
                "provider.login_persistence_failed",
                "Provider login could not be saved."
            );
        }

        openAiDispatcher.replaceAdapter(adapter);
        modelRegistry.replaceProvider(provider, descriptors);
        return new ProviderLoginResult(provider, descriptors);
    }

    private List<DiscoveredModel> discoverModels(URI baseUrl, String authKey) {
        List<DiscoveredModel> discovered;
        try {
            discovered = discoveryClient.discoverModels(baseUrl, authKey, DISCOVERY_PATHS, REQUEST_TIMEOUT);
        } catch (ModelProviderException error) {
            if (error.getMessage() != null && error.getMessage().contains(authKey)) {
                throw providerLoginFailure(
                    "provider.login_discovery_failed",
                    "Provider model discovery failed."
                );
            }
            throw error;
        } catch (RuntimeException error) {
            throw providerLoginFailure(
                "provider.login_discovery_failed",
                "Provider model discovery failed."
            );
        }
        if (discovered.isEmpty()) {
            throw providerLoginFailure(
                "model.discovery_unavailable",
                "Remote model discovery returned no usable models."
            );
        }
        return List.copyOf(discovered);
    }

    private static OpenAiCompatibleProviderAdapter chatCompletionsAdapter(
        String provider,
        URI baseUrl,
        String authKey
    ) {
        OpenAiProviderConfig config = new OpenAiProviderConfig(
            provider,
            baseUrl,
            Optional.empty(),
            "/v1/responses",
            authKey,
            RequestStyle.CHAT_COMPLETIONS,
            RequestStyle.CHAT_COMPLETIONS,
            TransportMode.SSE,
            REQUEST_TIMEOUT,
            MAX_RETRIES,
            Map.of("requires-reasoning-content-on-assistant-messages", true)
        );
        return new OpenAiCompatibleProviderAdapter(
            config,
            new WebSocketProviderTransport(),
            new HttpSseProviderTransport(),
            new HttpSseProviderTransport()
        );
    }

    private static String requireChannelName(String channelName) {
        if (channelName == null || !CHANNEL_NAME.matcher(channelName).matches()) {
            throw providerLoginFailure(
                "provider.login_invalid_channel_name",
                "Provider channel name must match [a-z0-9][a-z0-9_-]{0,63}."
            );
        }
        return channelName;
    }

    private static URI normalizeBaseUrl(String rawBaseUrl) {
        if (rawBaseUrl == null || rawBaseUrl.isBlank()) {
            throw providerLoginFailure(
                "provider.login_invalid_base_url",
                "Provider base URL must be an absolute HTTP(S) URL."
            );
        }
        URI parsed;
        try {
            parsed = URI.create(rawBaseUrl.trim());
        } catch (IllegalArgumentException error) {
            throw providerLoginFailure(
                "provider.login_invalid_base_url",
                "Provider base URL must be an absolute HTTP(S) URL."
            );
        }
        if (!parsed.isAbsolute()
            || parsed.isOpaque()
            || parsed.getHost() == null
            || parsed.getRawUserInfo() != null
            || parsed.getRawQuery() != null
            || parsed.getRawFragment() != null
            || !("http".equalsIgnoreCase(parsed.getScheme()) || "https".equalsIgnoreCase(parsed.getScheme()))) {
            throw providerLoginFailure(
                "provider.login_invalid_base_url",
                "Provider base URL must be an absolute HTTP(S) URL."
            );
        }
        String path = trimTrailingSlashes(parsed.getPath());
        try {
            return new URI(
                parsed.getScheme().toLowerCase(Locale.ROOT),
                null,
                parsed.getHost().toLowerCase(Locale.ROOT),
                parsed.getPort(),
                path,
                null,
                null
            );
        } catch (URISyntaxException error) {
            throw providerLoginFailure(
                "provider.login_invalid_base_url",
                "Provider base URL must be an absolute HTTP(S) URL."
            );
        }
    }

    private static String trimTrailingSlashes(String path) {
        if (path == null || path.isEmpty() || "/".equals(path)) {
            return null;
        }
        int end = path.length();
        while (end > 0 && path.charAt(end - 1) == '/') {
            end--;
        }
        return end == 0 ? null : path.substring(0, end);
    }

    private static String requireAuthKey(String authKey) {
        if (authKey == null || authKey.isBlank()) {
            throw providerLoginFailure(
                "provider.login_invalid_auth_key",
                "Provider auth key is required."
            );
        }
        return authKey;
    }

    private static ModelProviderException providerLoginFailure(String errorId, String message) {
        return new ModelProviderException(errorId, ErrorSeverity.ERROR, false, message);
    }
}
