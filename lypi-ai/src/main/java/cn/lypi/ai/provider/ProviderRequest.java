package cn.lypi.ai.provider;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

public record ProviderRequest(
    URI uri,
    Map<String, String> headers,
    String body,
    Optional<Duration> timeout
) {
    public ProviderRequest(URI uri, Map<String, String> headers, String body) {
        this(uri, headers, body, Optional.empty());
    }

    public ProviderRequest {
        headers = Map.copyOf(headers);
        timeout = timeout == null ? Optional.empty() : timeout;
    }
}
