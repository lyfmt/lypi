package cn.lypi.tool.web;

import cn.lypi.contracts.common.JsonSchema;
import cn.lypi.contracts.common.ProgressSink;
import cn.lypi.contracts.common.ToolProgress;
import cn.lypi.contracts.common.ValidationResult;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseContext;
import cn.lypi.contracts.web.WebSearchResponse;
import cn.lypi.contracts.web.WebSearchResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 执行商业 Web 搜索。
 */
public final class WebSearchTool extends AbstractWebTool {
    private static final int DEFAULT_MAX_RESULTS = 5;
    private static final int DEFAULT_MAX_RESULTS_LIMIT = 10;

    private final WebProviderRegistry providers;
    private final WebResultStore store;
    private final int defaultMaxResults;
    private final int maxResultsLimit;

    public WebSearchTool(WebProviderRegistry providers) {
        this(providers, WebResultStore.noop(), DEFAULT_MAX_RESULTS, DEFAULT_MAX_RESULTS_LIMIT);
    }

    public WebSearchTool(WebProviderRegistry providers, WebResultStore store) {
        this(providers, store, DEFAULT_MAX_RESULTS, DEFAULT_MAX_RESULTS_LIMIT);
    }

    public WebSearchTool(WebProviderRegistry providers, int maxResultsLimit) {
        this(providers, WebResultStore.noop(), maxResultsLimit, maxResultsLimit);
    }

    public WebSearchTool(WebProviderRegistry providers, int defaultMaxResults, int maxResultsLimit) {
        this(providers, WebResultStore.noop(), defaultMaxResults, maxResultsLimit);
    }

    public WebSearchTool(
        WebProviderRegistry providers,
        WebResultStore store,
        int defaultMaxResults,
        int maxResultsLimit
    ) {
        this.providers = Objects.requireNonNull(providers, "providers must not be null");
        this.store = store == null ? WebResultStore.noop() : store;
        this.maxResultsLimit = Math.max(1, maxResultsLimit);
        this.defaultMaxResults = Math.max(1, Math.min(this.maxResultsLimit, defaultMaxResults));
    }

    @Override
    public String name() {
        return "web_search";
    }

    @Override
    public String description() {
        return "Search the public web through the configured commercial search provider.";
    }

    @Override
    public boolean isReadOnly(Map<String, Object> input) {
        return false;
    }

    @Override
    public boolean isConcurrencySafe(Map<String, Object> input) {
        return false;
    }

    @Override
    public JsonSchema inputSchema() {
        return new JsonSchema(Map.of(
            "type", "object",
            "required", List.of("query"),
            "properties", Map.of(
                "query", Map.of("type", "string"),
                "maxResults", Map.of("type", "integer", "minimum", 1, "maximum", maxResultsLimit),
                "allowedDomains", Map.of("type", "array", "items", Map.of("type", "string")),
                "blockedDomains", Map.of("type", "array", "items", Map.of("type", "string")),
                "recency", Map.of("type", "string", "enum", List.of("hour", "day", "week", "month", "year")),
                "country", Map.of("type", "string"),
                "language", Map.of("type", "string"),
                "provider", Map.of("type", "string", "enum", providers.searchProviderNames()),
                "includeAnswer", Map.of("type", "boolean")
            )
        ));
    }

    @Override
    public ValidationResult validateInput(Map<String, Object> input, ToolUseContext context) {
        try {
            WebToolInputs.search(input, providers.searchProviderNames(), defaultMaxResults, maxResultsLimit);
            return new ValidationResult(true, List.of());
        } catch (RuntimeException exception) {
            return new ValidationResult(false, List.of(exception.getMessage()));
        }
    }

    @Override
    public PermissionDecision checkPermissions(Map<String, Object> input, ToolUseContext context) {
        return networkDecision(context, name(), Map.of("query", input.getOrDefault("query", "")));
    }

    @Override
    public ToolResult<String> execute(Map<String, Object> input, ToolUseContext context, ProgressSink progress) {
        try {
            WebSearchRequest request = WebToolInputs.search(input, providers.searchProviderNames(), defaultMaxResults, maxResultsLimit);
            progress.progress(ToolProgress.phase("searching", "搜索 Web"));
            WebSearchResponse response = searchProvider(request).search(request);
            WebStoredResult stored = store.save(storedResult(context, response));
            return success(context, render(response, stored.responseId()));
        } catch (RuntimeException exception) {
            return error(context, "Web 搜索失败: " + exception.getMessage());
        }
    }

    @Override
    public String renderForUser(Map<String, Object> input) {
        return "web_search query=" + input.getOrDefault("query", "");
    }

    private WebSearchProvider searchProvider(WebSearchRequest request) {
        if (request.provider().isPresent()) {
            return providers.searchProvider(request.provider());
        }
        return providers.fallbackSearchProvider(Optional.empty());
    }

    private WebStoredResult storedResult(ToolUseContext context, WebSearchResponse response) {
        return new WebStoredResult(
            context.sessionId(),
            context.messageId(),
            "",
            name(),
            Optional.ofNullable(response.query()).filter(query -> !query.isBlank()),
            Optional.empty(),
            response.results().stream()
                .map(this::storedItem)
                .toList(),
            Instant.now()
        );
    }

    private WebStoredItem storedItem(WebSearchResult result) {
        String content = result.content()
            .or(() -> result.snippet())
            .orElse("");
        return new WebStoredItem(
            result.url(),
            Optional.ofNullable(result.title()).filter(title -> !title.isBlank()),
            result.snippet(),
            content,
            Optional.of("markdown"),
            false,
            Optional.of("search")
        );
    }

    private String render(WebSearchResponse response, String responseId) {
        StringBuilder builder = new StringBuilder();
        builder.append("responseId=").append(responseId);
        appendCacheStatus(builder, responseId);
        builder.append("\nprovider=").append(response.provider());
        builder.append("\nquery=").append(response.query());
        response.answer().ifPresent(answer -> builder.append("\nanswer=").append(answer));
        builder.append("\nresults:");
        int index = 1;
        for (WebSearchResult result : response.results()) {
            builder.append("\n").append(index++).append(". ").append(result.title());
            builder.append("\n   url: ").append(result.url());
            result.snippet().ifPresent(snippet -> builder.append("\n   snippet: ").append(snippet));
            result.content().ifPresent(content -> builder.append("\n   content: ").append(content));
        }
        return builder.toString();
    }

    private void appendCacheStatus(StringBuilder builder, String responseId) {
        if (WebResultStore.DISABLED_RESPONSE_ID.equals(responseId)) {
            builder.append("\ncache=disabled");
            builder.append("\nnote=Web 结果缓存未启用，当前结果不可通过 get_search_content 取回。");
        }
    }
}
