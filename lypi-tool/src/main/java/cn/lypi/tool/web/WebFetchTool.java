package cn.lypi.tool.web;

import cn.lypi.contracts.common.JsonSchema;
import cn.lypi.contracts.common.ProgressSink;
import cn.lypi.contracts.common.ToolProgress;
import cn.lypi.contracts.common.ValidationResult;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseContext;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 抽取 URL 内容。
 */
public final class WebFetchTool extends AbstractWebTool {
    private final WebPageFetcher fetcher;
    private final WebContentCleaner cleaner;

    public WebFetchTool() {
        this(new JdkWebPageFetcher(Duration.ofSeconds(20)), new WebContentCleaner());
    }

    public WebFetchTool(Duration timeout) {
        this(new JdkWebPageFetcher(timeout), new WebContentCleaner());
    }

    WebFetchTool(WebPageFetcher fetcher) {
        this(fetcher, new WebContentCleaner());
    }

    WebFetchTool(WebPageFetcher fetcher, WebContentCleaner cleaner) {
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher must not be null");
        this.cleaner = Objects.requireNonNull(cleaner, "cleaner must not be null");
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
            return success(context, render(fetched, content, request));
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
        WebFetchRequest request
    ) {
        StringBuilder builder = new StringBuilder();
        builder.append("source=local");
        builder.append("\nurl=").append(request.url());
        builder.append("\nfinalUrl=").append(fetched.finalUrl());
        content.title().ifPresent(title -> builder.append("\ntitle=").append(title));
        builder.append("\nformat=").append(request.format());
        builder.append("\ncontent:\n").append(content.content());
        return builder.toString();
    }
}
