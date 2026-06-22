package cn.lypi.tool.web;

import cn.lypi.contracts.common.JsonSchema;
import cn.lypi.contracts.common.ProgressSink;
import cn.lypi.contracts.common.ToolProgress;
import cn.lypi.contracts.common.ValidationResult;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseContext;
import cn.lypi.contracts.web.WebFetchResponse;
import java.util.List;
import java.util.Map;

/**
 * 抽取 URL 内容。
 */
public final class WebFetchTool extends AbstractWebTool {
    private final WebProviderRegistry providers;

    public WebFetchTool(WebProviderRegistry providers) {
        this.providers = providers;
    }

    @Override
    public String name() {
        return "web_fetch";
    }

    @Override
    public String description() {
        return "Fetch and extract public web page content through the configured provider.";
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
                "maxChars", Map.of("type", "integer", "minimum", 1, "maximum", 50_000),
                "provider", Map.of("type", "string", "enum", providers.fetchProviderNames())
            )
        ));
    }

    @Override
    public ValidationResult validateInput(Map<String, Object> input, ToolUseContext context) {
        try {
            WebFetchRequest request = WebToolInputs.fetch(input, providers.fetchProviderNames());
            WebUrlPolicy.check(request.url());
            return new ValidationResult(true, List.of());
        } catch (RuntimeException exception) {
            return new ValidationResult(false, List.of(exception.getMessage()));
        }
    }

    @Override
    public PermissionDecision checkPermissions(Map<String, Object> input, ToolUseContext context) {
        try {
            WebFetchRequest request = WebToolInputs.fetch(input, providers.fetchProviderNames());
            WebUrlPolicy.CheckedUrl checkedUrl = WebUrlPolicy.check(request.url());
            return networkDecision(context, name(), Map.of("domain", checkedUrl.host()));
        } catch (RuntimeException exception) {
            return networkDecision(context, name(), Map.of("urlError", exception.getMessage()));
        }
    }

    @Override
    public ToolResult<String> execute(Map<String, Object> input, ToolUseContext context, ProgressSink progress) {
        try {
            WebFetchRequest request = WebToolInputs.fetch(input, providers.fetchProviderNames());
            WebUrlPolicy.check(request.url());
            progress.progress(ToolProgress.phase("fetching", "抽取网页内容"));
            WebFetchResponse response = providers.fetchProvider(request.provider()).fetch(request);
            return success(context, render(response, request.maxChars()));
        } catch (RuntimeException exception) {
            return error(context, "Web 抽取失败: " + exception.getMessage());
        }
    }

    @Override
    public String renderForUser(Map<String, Object> input) {
        return "web_fetch url=" + input.getOrDefault("url", "");
    }

    private String render(WebFetchResponse response, int maxChars) {
        String content = response.content();
        if (content.length() > maxChars) {
            content = content.substring(0, maxChars);
        }
        StringBuilder builder = new StringBuilder();
        builder.append("provider=").append(response.provider());
        builder.append("\nurl=").append(response.url());
        response.title().ifPresent(title -> builder.append("\ntitle=").append(title));
        builder.append("\nformat=").append(response.format());
        builder.append("\ncontent:\n").append(content);
        return builder.toString();
    }
}
