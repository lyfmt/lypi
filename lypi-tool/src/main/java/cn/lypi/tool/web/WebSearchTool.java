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
import java.util.List;
import java.util.Map;

/**
 * 执行商业 Web 搜索。
 */
public final class WebSearchTool extends AbstractWebTool {
    private final WebProviderRegistry providers;

    public WebSearchTool(WebProviderRegistry providers) {
        this.providers = providers;
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
    public JsonSchema inputSchema() {
        return new JsonSchema(Map.of(
            "type", "object",
            "required", List.of("query"),
            "properties", Map.of(
                "query", Map.of("type", "string"),
                "maxResults", Map.of("type", "integer", "minimum", 1, "maximum", 10),
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
            WebToolInputs.search(input);
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
            WebSearchRequest request = WebToolInputs.search(input);
            progress.progress(ToolProgress.phase("searching", "搜索 Web"));
            WebSearchResponse response = providers.searchProvider(request.provider()).search(request);
            return success(context, render(response));
        } catch (RuntimeException exception) {
            return error(context, "Web 搜索失败: " + exception.getMessage());
        }
    }

    @Override
    public String renderForUser(Map<String, Object> input) {
        return "web_search query=" + input.getOrDefault("query", "");
    }

    private String render(WebSearchResponse response) {
        StringBuilder builder = new StringBuilder();
        builder.append("provider=").append(response.provider());
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
}
