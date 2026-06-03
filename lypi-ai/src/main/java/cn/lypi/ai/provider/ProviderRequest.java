package cn.lypi.ai.provider;

import java.net.URI;
import java.util.Map;

public record ProviderRequest(
    URI uri,
    Map<String, String> headers,
    String body
) {
    public ProviderRequest {
        headers = Map.copyOf(headers);
    }
}
