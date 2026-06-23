package cn.lypi.tool.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.web.WebSearchResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class FallbackWebSearchProviderTest {
    @Test
    void triesNextProviderWhenFirstFails() {
        List<String> calls = new ArrayList<>();
        FallbackWebSearchProvider provider = new FallbackWebSearchProvider(List.of(
            failing("tavily", calls, "provider down"),
            success("brave", calls)
        ));

        WebSearchResponse response = provider.search(request());

        assertEquals("brave", response.provider());
        assertEquals(List.of("tavily", "brave"), calls);
    }

    @Test
    void throwsCombinedSummaryWhenAllProvidersFail() {
        FallbackWebSearchProvider provider = new FallbackWebSearchProvider(List.of(
            failing("tavily", new ArrayList<>(), "provider down"),
            failing("brave", new ArrayList<>(), "rate limit")
        ));

        WebProviderException exception = assertThrows(WebProviderException.class, () -> provider.search(request()));

        assertTrue(exception.getMessage().contains("tavily: provider down"));
        assertTrue(exception.getMessage().contains("brave: rate limit"));
    }

    private WebSearchProvider success(String name, List<String> calls) {
        return new WebSearchProvider() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public WebSearchResponse search(WebSearchRequest request) {
                calls.add(name);
                return new WebSearchResponse(name, request.query(), Optional.empty(), List.of(), Optional.empty());
            }
        };
    }

    private WebSearchProvider failing(String name, List<String> calls, String message) {
        return new WebSearchProvider() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public WebSearchResponse search(WebSearchRequest request) {
                calls.add(name);
                throw new WebProviderException(message);
            }
        };
    }

    private WebSearchRequest request() {
        return new WebSearchRequest(
            "java",
            5,
            List.of(),
            List.of(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            false
        );
    }
}
