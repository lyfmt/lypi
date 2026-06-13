package cn.lypi.agent.branch;

import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.model.AssistantDone;
import cn.lypi.contracts.model.AssistantError;
import cn.lypi.contracts.model.AssistantEventStream;
import cn.lypi.contracts.model.AssistantStreamEvent;
import cn.lypi.contracts.model.TextDelta;
import cn.lypi.contracts.model.TokenUsage;
import cn.lypi.contracts.runtime.AiProviderRuntimePort;
import java.util.regex.Pattern;

/**
 * 使用当前 AI provider 为离开的会话分支生成 summary。
 */
public final class AiBranchSummarizer {
    private static final TokenUsage ZERO_USAGE = new TokenUsage(0, 0, 0, 0);
    private static final String PREAMBLE = """
        The user explored a different conversation branch before returning here.
        Summary of that exploration:

        """;
    private static final Pattern ANALYSIS_BLOCK = Pattern.compile("(?is)<analysis>.*?</analysis>");
    private static final Pattern SUMMARY_BLOCK = Pattern.compile("(?is).*?<summary>(.*?)</summary>.*");
    private static final Pattern FENCE = Pattern.compile("(?is)^```[a-zA-Z0-9_-]*\\s*(.*?)\\s*```$");

    private final AiProviderRuntimePort provider;
    private final BranchSummaryContextBuilder contextBuilder;

    public AiBranchSummarizer(AiProviderRuntimePort provider, BranchSummaryContextBuilder contextBuilder) {
        this.provider = provider;
        this.contextBuilder = contextBuilder;
    }

    /**
     * 生成 branch summary。
     */
    public BranchSummaryResult summarize(BranchSummaryRequest request) {
        StringBuilder text = new StringBuilder();
        TokenUsage usage = ZERO_USAGE;
        ContextSnapshot summaryContext = contextBuilder.build(request);
        try (AssistantEventStream stream = provider.stream(summaryContext, request.abortSignal())) {
            for (AssistantStreamEvent event : stream) {
                if (event instanceof TextDelta delta) {
                    text.append(delta.text());
                } else if (event instanceof AssistantDone done) {
                    usage = done.usage().orElse(ZERO_USAGE);
                } else if (event instanceof AssistantError error) {
                    throw new IllegalStateException("branch summary provider error: " + error.message());
                }
                if (request.abortSignal().aborted()) {
                    throw new IllegalStateException("branch summary aborted");
                }
            }
        }
        if (request.abortSignal().aborted()) {
            throw new IllegalStateException("branch summary aborted");
        }
        String summary = cleanSummary(text.toString());
        if (summary.isBlank()) {
            throw new IllegalStateException("branch summary provider returned empty output");
        }
        return new BranchSummaryResult(PREAMBLE + summary, usage);
    }

    private String cleanSummary(String raw) {
        String cleaned = raw == null ? "" : raw.strip();
        cleaned = stripFence(cleaned);
        cleaned = ANALYSIS_BLOCK.matcher(cleaned).replaceAll("").strip();
        cleaned = extractSummaryBlock(cleaned);
        cleaned = stripFence(cleaned);
        return cleaned.strip();
    }

    private String stripFence(String text) {
        return FENCE.matcher(text.strip()).replaceFirst("$1").strip();
    }

    private String extractSummaryBlock(String text) {
        return SUMMARY_BLOCK.matcher(text).replaceFirst("$1").strip();
    }
}
