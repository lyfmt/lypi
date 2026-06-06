package cn.lypi.agent.compact;

import cn.lypi.contracts.model.AssistantDone;
import cn.lypi.contracts.model.AssistantError;
import cn.lypi.contracts.model.AssistantEventStream;
import cn.lypi.contracts.model.AssistantStreamEvent;
import cn.lypi.contracts.model.TextDelta;
import cn.lypi.contracts.model.TokenUsage;
import cn.lypi.contracts.runtime.AiProviderRuntimePort;
import java.util.regex.Pattern;

public final class AiCompactionSummarizer implements CompactionSummarizer {
    private static final TokenUsage ZERO_USAGE = new TokenUsage(0, 0, 0, 0);
    private static final Pattern ANALYSIS_BLOCK = Pattern.compile("(?is)<analysis>.*?</analysis>");
    private static final Pattern SUMMARY_BLOCK = Pattern.compile("(?is).*?<summary>(.*?)</summary>.*");
    private static final Pattern FENCE = Pattern.compile("(?is)^```[a-zA-Z0-9_-]*\\s*(.*?)\\s*```$");

    private final AiProviderRuntimePort provider;
    private final CompactSummaryContextBuilder contextBuilder;
    private final CompactionSummarizer deterministicFallback;
    private final CompactionSummaryOptions options;

    public AiCompactionSummarizer(
        AiProviderRuntimePort provider,
        CompactSummaryContextBuilder contextBuilder,
        CompactionSummarizer deterministicFallback,
        CompactionSummaryOptions options
    ) {
        this.provider = provider;
        this.contextBuilder = contextBuilder;
        this.deterministicFallback = deterministicFallback;
        this.options = options;
    }

    @Override
    public CompactSummaryResult summarize(CompactSummaryRequest request) {
        try {
            return summarizeWithAi(request);
        } catch (RuntimeException exception) {
            if (exception instanceof SummaryAbortedException) {
                throw exception;
            }
            return fallback(request, exception);
        }
    }

    private CompactSummaryResult summarizeWithAi(CompactSummaryRequest request) {
        StringBuilder text = new StringBuilder();
        TokenUsage usage = ZERO_USAGE;
        try (AssistantEventStream stream = provider.stream(contextBuilder.build(request, options), request.abortSignal())) {
            for (AssistantStreamEvent event : stream) {
                if (event instanceof TextDelta delta) {
                    text.append(delta.text());
                } else if (event instanceof AssistantDone done) {
                    usage = done.usage().orElse(ZERO_USAGE);
                } else if (event instanceof AssistantError error) {
                    throw new IllegalStateException("summary provider error: " + error.message());
                }
                if (request.abortSignal().aborted()) {
                    throw new SummaryAbortedException();
                }
            }
        }
        if (request.abortSignal().aborted()) {
            throw new SummaryAbortedException();
        }

        String summary = cleanSummary(text.toString());
        if (summary.isBlank()) {
            throw new IllegalStateException("summary provider returned empty output");
        }
        return new CompactSummaryResult(summary, usage);
    }

    private CompactSummaryResult fallback(CompactSummaryRequest request, RuntimeException cause) {
        if (options.fallbackPolicy() == CompactionSummaryFallbackPolicy.FALLBACK_DETERMINISTIC) {
            return deterministicFallback.summarize(request);
        }
        throw new IllegalStateException("AI compaction summary failed: " + cause.getMessage(), cause);
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

    private static final class SummaryAbortedException extends IllegalStateException {
        private SummaryAbortedException() {
            super("summary aborted");
        }
    }
}
