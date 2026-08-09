package cn.lypi.boot.ai;

import cn.lypi.ai.ProviderAdapterApiProvider;
import cn.lypi.ai.RuntimeModelRegistry;
import cn.lypi.ai.model.RemoteModelDiscoveryClient;
import cn.lypi.ai.provider.RequestStyle;
import cn.lypi.ai.provider.TransportMode;
import cn.lypi.ai.provider.openai.OpenAiCompatibleProviderAdapter;
import cn.lypi.ai.provider.openai.OpenAiProviderConfig;
import cn.lypi.ai.transport.HttpSseProviderTransport;
import cn.lypi.ai.transport.WebSocketProviderTransport;
import cn.lypi.contracts.error.ErrorSeverity;
import cn.lypi.contracts.error.ModelProviderException;
import cn.lypi.contracts.model.ApiStyle;
import cn.lypi.contracts.model.CostProfile;
import cn.lypi.contracts.model.ModelDescriptor;
import cn.lypi.contracts.runtime.ProviderLoginPort;
import cn.lypi.contracts.runtime.ProviderLoginResult;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Registers a verified OpenAI-compatible Chat Completions provider for the current process. */
public final class OpenAiCompatibleProviderLoginService implements ProviderLoginPort {
    private static final List<String> DISCOVERY_PATHS = List.of("/models", "/model");
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_RETRIES = 3;

    private final RemoteModelDiscoveryClient discoveryClient;
    private final RuntimeModelRegistry modelRegistry;
    private final ProviderAdapterApiProvider openAiDispatcher;
    private final LoginProviderPropertiesStore propertiesStore;

    public OpenAiCompatibleProviderLoginService(
        RemoteModelDiscoveryClient discoveryClient,
        RuntimeModelRegistry modelRegistry,
        ProviderAdapterApiProvider openAiDispatcher,
        LoginProviderPropertiesStore propertiesStore
    ) {
        this.discoveryClient = Objects.requireNonNull(discoveryClient, "discoveryClient");
        this.modelRegistry = Objects.requireNonNull(modelRegistry, "modelRegistry");
        this.openAiDispatcher = Objects.requireNonNull(openAiDispatcher, "openAiDispatcher");
        this.propertiesStore = Objects.requireNonNull(propertiesStore, "propertiesStore");
    }

    @Override
    public ProviderLoginResult register(String rawBaseUrl, String authKey) {
        URI baseUrl = normalizeBaseUrl(rawBaseUrl);
        String requiredAuthKey = requireAuthKey(authKey);
        String provider = providerId(baseUrl);
        List<String> modelIds = discoverModelIds(baseUrl, requiredAuthKey);
        List<ModelDescriptor> descriptors = descriptors(provider, baseUrl, modelIds);
        OpenAiCompatibleProviderAdapter adapter = chatCompletionsAdapter(provider, baseUrl, requiredAuthKey);

        try {
            propertiesStore.save(provider, baseUrl, requiredAuthKey, modelIds);
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

    private List<String> discoverModelIds(URI baseUrl, String authKey) {
        List<String> discovered;
        try {
            discovered = discoveryClient.discover(baseUrl, authKey, DISCOVERY_PATHS, REQUEST_TIMEOUT);
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
        List<String> modelIds = discovered.stream()
            .filter(Objects::nonNull)
            .filter(modelId -> !modelId.isBlank())
            .distinct()
            .sorted()
            .toList();
        if (modelIds.isEmpty()) {
            throw providerLoginFailure(
                "model.discovery_unavailable",
                "Remote model discovery returned no usable models."
            );
        }
        return modelIds;
    }

    private static List<ModelDescriptor> descriptors(String provider, URI baseUrl, List<String> modelIds) {
        return modelIds.stream()
            .map(modelId -> new ModelDescriptor(
                provider,
                modelId,
                baseUrl,
                ApiStyle.OPENAI_COMPATIBLE,
                0,
                0,
                false,
                false,
                new CostProfile(BigDecimal.ZERO, BigDecimal.ZERO, "USD"),
                Map.of()
            ))
            .toList();
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
            Map.of()
        );
        return new OpenAiCompatibleProviderAdapter(
            config,
            new WebSocketProviderTransport(),
            new HttpSseProviderTransport(),
            new HttpSseProviderTransport()
        );
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

    private static String providerId(URI baseUrl) {
        String canonicalUrl = baseUrl.toString();
        String readable = sanitizeProviderPart(baseUrl.getHost() + (baseUrl.getPath() == null ? "" : baseUrl.getPath()));
        String prefix = readable.isBlank() ? "provider" : abbreviate(readable, 36);
        return "login-" + prefix + "-" + sha256(canonicalUrl).substring(0, 10);
    }

    private static String sanitizeProviderPart(String value) {
        StringBuilder sanitized = new StringBuilder(value.length());
        boolean previousDash = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (isAsciiLetterOrDigit(character)) {
                sanitized.append(Character.toLowerCase(character));
                previousDash = false;
            } else if (!previousDash) {
                sanitized.append('-');
                previousDash = true;
            }
        }
        int start = sanitized.length() > 0 && sanitized.charAt(0) == '-' ? 1 : 0;
        int end = sanitized.length() > start && sanitized.charAt(sanitized.length() - 1) == '-'
            ? sanitized.length() - 1
            : sanitized.length();
        return sanitized.substring(start, end);
    }

    private static boolean isAsciiLetterOrDigit(char character) {
        return (character >= 'a' && character <= 'z')
            || (character >= 'A' && character <= 'Z')
            || (character >= '0' && character <= '9');
    }

    private static String abbreviate(String value, int maximumLength) {
        return value.length() <= maximumLength ? value : value.substring(0, maximumLength);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static ModelProviderException providerLoginFailure(String errorId, String message) {
        return new ModelProviderException(errorId, ErrorSeverity.ERROR, false, message);
    }
}
