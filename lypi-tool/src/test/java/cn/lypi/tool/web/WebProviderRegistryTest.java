package cn.lypi.tool.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.web.WebSearchResponse;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class WebProviderRegistryTest {
    @Test
    void selectsDefaultSearchProvider() {
        FakeSearchProvider tavily = new FakeSearchProvider("tavily");
        WebProviderRegistry registry = new WebProviderRegistry(
            "tavily",
            Map.of("tavily", tavily)
        );

        assertSame(tavily, registry.searchProvider(Optional.empty()));
    }

    @Test
    void selectsRequestedSearchProvider() {
        FakeSearchProvider tavily = new FakeSearchProvider("tavily");
        FakeSearchProvider brave = new FakeSearchProvider("brave");
        WebProviderRegistry registry = new WebProviderRegistry(
            "tavily",
            Map.of("tavily", tavily, "brave", brave)
        );

        assertSame(brave, registry.searchProvider(Optional.of("brave")));
    }

    @Test
    void rejectsUnknownSearchProvider() {
        WebProviderRegistry registry = new WebProviderRegistry(
            "tavily",
            Map.of("tavily", new FakeSearchProvider("tavily"))
        );

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> registry.searchProvider(Optional.of("perplexity"))
        );

        assertTrue(exception.getMessage().contains("perplexity"));
        assertTrue(exception.getMessage().contains("tavily"));
    }

    @Test
    void exposesProviderNamesInStableOrder() {
        WebProviderRegistry registry = new WebProviderRegistry(
            "tavily",
            Map.of(
                "perplexity", new FakeSearchProvider("perplexity"),
                "tavily", new FakeSearchProvider("tavily"),
                "brave", new FakeSearchProvider("brave")
            )
        );

        assertEquals(List.of("brave", "perplexity", "tavily"), registry.searchProviderNames());
    }

    @Test
    void returnsFallbackProviderWithDefaultFirstThenRegistrationOrder() {
        FakeSearchProvider tavily = new FakeSearchProvider("tavily");
        FakeSearchProvider brave = new FakeSearchProvider("brave");
        FakeSearchProvider perplexity = new FakeSearchProvider("perplexity");
        Map<String, WebSearchProvider> providers = new LinkedHashMap<>();
        providers.put("brave", brave);
        providers.put("tavily", tavily);
        providers.put("perplexity", perplexity);
        WebProviderRegistry registry = new WebProviderRegistry("tavily", providers);

        WebSearchResponse response = registry.fallbackSearchProvider(Optional.empty()).search(new WebSearchRequest(
            "java",
            5,
            List.of(),
            List.of(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            false
        ));

        assertEquals("tavily", response.provider());
    }

    @Test
    void returnsFallbackProviderWithRegistrationOrderWhenDefaultIsUnavailable() {
        FakeSearchProvider exa = new FakeSearchProvider("exa");
        FakeSearchProvider brave = new FakeSearchProvider("brave");
        Map<String, WebSearchProvider> providers = new LinkedHashMap<>();
        providers.put("exa", exa);
        providers.put("brave", brave);
        WebProviderRegistry registry = new WebProviderRegistry("missing", providers);

        WebSearchResponse response = registry.fallbackSearchProvider(Optional.empty()).search(new WebSearchRequest(
            "java",
            5,
            List.of(),
            List.of(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            false
        ));

        assertEquals("exa", response.provider());
    }

    @Test
    void requestedFallbackProviderReturnsOnlyRequestedProvider() {
        FakeSearchProvider tavily = new FakeSearchProvider("tavily");
        FakeSearchProvider brave = new FakeSearchProvider("brave");
        WebProviderRegistry registry = new WebProviderRegistry(
            "tavily",
            Map.of("tavily", tavily, "brave", brave)
        );

        assertSame(brave, registry.fallbackSearchProvider(Optional.of("brave")));
    }

    private record FakeSearchProvider(String name) implements WebSearchProvider {
        @Override
        public WebSearchResponse search(WebSearchRequest request) {
            return new WebSearchResponse(name, request.query(), Optional.empty(), List.of(), Optional.empty());
        }
    }
}
