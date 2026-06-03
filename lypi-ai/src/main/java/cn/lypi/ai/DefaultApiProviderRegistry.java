package cn.lypi.ai;

import cn.lypi.contracts.model.ApiStyle;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class DefaultApiProviderRegistry implements ApiProviderRegistry {
    private final Map<ApiStyle, ApiProvider> providers;

    public DefaultApiProviderRegistry(List<? extends ApiProvider> providers) {
        Objects.requireNonNull(providers, "providers");
        EnumMap<ApiStyle, ApiProvider> indexed = new EnumMap<>(ApiStyle.class);
        for (ApiProvider provider : List.copyOf(providers)) {
            ApiProvider previous = indexed.putIfAbsent(provider.apiStyle(), provider);
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate API provider for style: " + provider.apiStyle());
            }
        }
        this.providers = Map.copyOf(indexed);
    }

    @Override
    public Optional<ApiProvider> find(ApiStyle apiStyle) {
        return Optional.ofNullable(providers.get(Objects.requireNonNull(apiStyle, "apiStyle")));
    }
}
