package cn.lypi.tool.web;

import cn.lypi.contracts.common.JsonSchema;
import cn.lypi.contracts.common.ProgressSink;
import cn.lypi.contracts.common.ToolProgress;
import cn.lypi.contracts.common.ValidationResult;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseContext;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 抽取 URL 内容。
 */
public final class WebFetchTool extends AbstractWebTool {
    private final WebPageFetcher fetcher;
    private final WebContentCleaner cleaner;
    private final WebResultStore store;

    public WebFetchTool() {
        this(defaultFetcher(Duration.ofSeconds(20)), defaultCleaner(), WebResultStore.noop());
    }

    public WebFetchTool(Duration timeout) {
        this(defaultFetcher(timeout), defaultCleaner(), WebResultStore.noop());
    }

    WebFetchTool(WebPageFetcher fetcher) {
        this(fetcher, new WebContentCleaner(), WebResultStore.noop());
    }

    WebFetchTool(WebPageFetcher fetcher, WebContentCleaner cleaner) {
        this(fetcher, cleaner, WebResultStore.noop());
    }

    public WebFetchTool(WebPageFetcher fetcher, WebContentCleaner cleaner, WebResultStore store) {
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher must not be null");
        this.cleaner = Objects.requireNonNull(cleaner, "cleaner must not be null");
        this.store = store == null ? WebResultStore.noop() : store;
    }

    @Override
    public String name() {
        return "web_fetch";
    }

    @Override
    public String description() {
        return "Fetch and clean public web page content locally.";
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
            "required", List.of("url"),
            "properties", Map.of(
                "url", Map.of("type", "string"),
                "query", Map.of("type", "string"),
                "format", Map.of("type", "string", "enum", List.of("markdown", "text")),
                "maxChars", Map.of("type", "integer", "minimum", 1, "maximum", 50_000)
            )
        ));
    }

    @Override
    public ValidationResult validateInput(Map<String, Object> input, ToolUseContext context) {
        try {
            WebFetchRequest request = WebToolInputs.fetch(input);
            WebUrlPolicy.check(request.url());
            return new ValidationResult(true, List.of());
        } catch (RuntimeException exception) {
            return new ValidationResult(false, List.of(exception.getMessage()));
        }
    }

    @Override
    public PermissionDecision checkPermissions(Map<String, Object> input, ToolUseContext context) {
        try {
            WebFetchRequest request = WebToolInputs.fetch(input);
            WebUrlPolicy.CheckedUrl checkedUrl = WebUrlPolicy.check(request.url());
            return networkDecision(context, name(), Map.of("domain", checkedUrl.host()));
        } catch (RuntimeException exception) {
            return networkDecision(context, name(), Map.of("urlError", exception.getMessage()));
        }
    }

    @Override
    public ToolResult<String> execute(Map<String, Object> input, ToolUseContext context, ProgressSink progress) {
        try {
            WebFetchRequest request = WebToolInputs.fetch(input);
            WebUrlPolicy.check(request.url());
            progress.progress(ToolProgress.phase("fetching", "抽取网页内容"));
            WebPageFetchResult fetched = fetcher.fetch(request.url());
            WebContentCleaner.CleanedContent content = cleaner.clean(
                fetched,
                request.format(),
                request.query(),
                request.maxChars()
            );
            WebStoredResult stored = store.save(storedResult(context, fetched, content, request));
            return success(context, render(fetched, content, request, stored.responseId()));
        } catch (RuntimeException exception) {
            return error(context, "Web 抽取失败: " + exception.getMessage());
        }
    }

    @Override
    public String renderForUser(Map<String, Object> input) {
        return "web_fetch url=" + input.getOrDefault("url", "");
    }

    private String render(
        WebPageFetchResult fetched,
        WebContentCleaner.CleanedContent content,
        WebFetchRequest request,
        String responseId
    ) {
        StringBuilder builder = new StringBuilder();
        builder.append("responseId=").append(responseId);
        appendCacheStatus(builder, responseId);
        builder.append("\nsource=").append(fetched.source());
        builder.append("\nurl=").append(request.url());
        builder.append("\nfinalUrl=").append(fetched.finalUrl());
        content.title().ifPresent(title -> builder.append("\ntitle=").append(title));
        builder.append("\nformat=").append(request.format());
        builder.append("\ncontent:\n").append(content.content());
        return builder.toString();
    }

    private WebStoredResult storedResult(
        ToolUseContext context,
        WebPageFetchResult fetched,
        WebContentCleaner.CleanedContent content,
        WebFetchRequest request
    ) {
        return new WebStoredResult(
            context.sessionId(),
            context.messageId(),
            "",
            name(),
            request.query(),
            Optional.of(request.url()),
            List.of(new WebStoredItem(
                fetched.finalUrl(),
                content.title(),
                Optional.empty(),
                content.content(),
                Optional.of(request.format()),
                false,
                Optional.of(fetched.source())
            )),
            Instant.now()
        );
    }

    public static WebPageFetcher defaultFetcher(Duration timeout) {
        return defaultFetcher(timeout, true, JinaReaderFetcher.DEFAULT_ENDPOINT, 200);
    }

    public static WebPageFetcher defaultFetcher(
        Duration timeout,
        boolean jinaEnabled,
        String jinaEndpoint,
        int minBodyChars
    ) {
        WebPageFetcher local = new JdkWebPageFetcher(timeout);
        if (!jinaEnabled) {
            return local;
        }
        return new FallbackWebPageFetcher(
            local,
            new JinaReaderFetcher(timeout, jinaEndpoint),
            minBodyChars
        );
    }

    public static WebContentCleaner defaultCleaner() {
        return new WebContentCleaner(new JsoupWebContentCleaner());
    }

    private void appendCacheStatus(StringBuilder builder, String responseId) {
        if (WebResultStore.DISABLED_RESPONSE_ID.equals(responseId)) {
            builder.append("\ncache=disabled");
            builder.append("\nnote=Web 结果缓存未启用，当前结果不可通过 get_search_content 取回。");
        }
    }
}
