package cn.lypi.tool.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.web.WebFetchResponse;
import cn.lypi.contracts.web.WebSearchResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class WebProviderRegistryTest {
    @Test
    void selectsDefaultSearchProvider() {
        FakeSearchProvider tavily = new FakeSearchProvider("tavily");
        WebProviderRegistry registry = new WebProviderRegistry(
            "tavily",
            Map.of("tavily", tavily),
            Map.of()
        );

        assertSame(tavily, registry.searchProvider(Optional.empty()));
    }

    @Test
    void selectsRequestedSearchProvider() {
        FakeSearchProvider tavily = new FakeSearchProvider("tavily");
        FakeSearchProvider brave = new FakeSearchProvider("brave");
        WebProviderRegistry registry = new WebProviderRegistry(
            "tavily",
            Map.of("tavily", tavily, "brave", brave),
            Map.of()
        );

        assertSame(brave, registry.searchProvider(Optional.of("brave")));
    }

    @Test
    void rejectsUnknownSearchProvider() {
        WebProviderRegistry registry = new WebProviderRegistry(
            "tavily",
            Map.of("tavily", new FakeSearchProvider("tavily")),
            Map.of()
        );

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> registry.searchProvider(Optional.of("perplexity"))
        );

        assertTrue(exception.getMessage().contains("perplexity"));
        assertTrue(exception.getMessage().contains("tavily"));
    }

    @Test
    void selectsFetchProviderSeparately() {
        FakeFetchProvider tavily = new FakeFetchProvider("tavily");
        WebProviderRegistry registry = new WebProviderRegistry(
            "brave",
            Map.of("brave", new FakeSearchProvider("brave")),
            Map.of("tavily", tavily)
        );

        assertSame(tavily, registry.fetchProvider(Optional.empty()));
    }

    @Test
    void exposesProviderNamesInStableOrder() {
        WebProviderRegistry registry = new WebProviderRegistry(
            "tavily",
            Map.of(
                "perplexity", new FakeSearchProvider("perplexity"),
                "tavily", new FakeSearchProvider("tavily"),
                "brave", new FakeSearchProvider("brave")
            ),
            Map.of("tavily", new FakeFetchProvider("tavily"))
        );

        assertEquals(List.of("brave", "perplexity", "tavily"), registry.searchProviderNames());
        assertEquals(List.of("tavily"), registry.fetchProviderNames());
    }

    private record FakeSearchProvider(String name) implements WebSearchProvider {
        @Override
        public WebSearchResponse search(WebSearchRequest request) {
            return new WebSearchResponse(name, request.query(), Optional.empty(), List.of(), Optional.empty());
        }
    }

    private record FakeFetchProvider(String name) implements WebFetchProvider {
        @Override
        public WebFetchResponse fetch(WebFetchRequest request) {
            return new WebFetchResponse(name, request.url(), Optional.empty(), "", "markdown", Optional.empty(), Optional.empty());
        }
    }
}
