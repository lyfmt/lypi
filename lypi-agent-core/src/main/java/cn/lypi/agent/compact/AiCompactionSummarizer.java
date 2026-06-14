package cn.lypi.agent.compact;

import cn.lypi.contracts.model.AssistantDone;
import cn.lypi.contracts.model.AssistantError;
import cn.lypi.contracts.model.AssistantEventStream;
import cn.lypi.contracts.model.AssistantStreamEvent;
import cn.lypi.contracts.model.TextDelta;
import cn.lypi.contracts.model.TokenUsage;
import cn.lypi.contracts.runtime.AiProviderRuntimePort;
import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContextSnapshot;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

public final class AiCompactionSummarizer implements CompactionSummarizer {
    private static final TokenUsage ZERO_USAGE = new TokenUsage(0, 0, 0, 0);
    private static final Pattern ANALYSIS_BLOCK = Pattern.compile("(?is)<analysis>.*?</analysis>");
    private static final Pattern SUMMARY_BLOCK = Pattern.compile("(?is).*?<summary>(.*?)</summary>.*");
    private static final Pattern FENCE = Pattern.compile("(?is)^```[a-zA-Z0-9_-]*\\s*(.*?)\\s*```$");

    private final AiProviderRuntimePort provider;
    private final CompactSummaryContextBuilder contextBuilder;
    private final CompactionSummaryOptions options;

    public AiCompactionSummarizer(
        AiProviderRuntimePort provider,
        CompactSummaryContextBuilder contextBuilder,
        CompactionSummaryOptions options
    ) {
        this.provider = provider;
        this.contextBuilder = contextBuilder;
        this.options = options;
    }

    @Override
    public CompactSummaryResult summarize(CompactSummaryRequest request) {
        try {
            return summarizeWithRetry(request);
        } catch (RuntimeException exception) {
            if (exception instanceof SummaryAbortedException) {
                throw exception;
            }
            return fallback(request, exception);
        }
    }

    private CompactSummaryResult summarizeWithRetry(CompactSummaryRequest request) {
        List<AgentMessage> messagesToSummarize = request.context().messages();
        int promptTooLongAttempts = 0;

        while (true) {
            try {
                return summarizeWithAi(request, messagesToSummarize);
            } catch (PromptTooLongException exception) {
                promptTooLongAttempts++;
                Optional<List<AgentMessage>> truncated = promptTooLongAttempts <= CompactionPromptTooLongRetry.MAX_PTL_RETRIES
                    ? exception.truncate(messagesToSummarize)
                    : Optional.empty();
                if (truncated.isEmpty()) {
                    throw new IllegalStateException(
                        "compact summary prompt too long after " + promptTooLongAttempts + " attempt(s)",
                        exception
                    );
                }
                messagesToSummarize = truncated.orElseThrow();
            }
        }
    }

    private CompactSummaryResult summarizeWithAi(CompactSummaryRequest request, List<AgentMessage> messagesToSummarize) {
        StringBuilder text = new StringBuilder();
        TokenUsage usage = ZERO_USAGE;
        ContextSnapshot summaryContext = contextBuilder.build(request, messagesToSummarize);
        try (AssistantEventStream stream = provider.stream(summaryContext, request.abortSignal())) {
            for (AssistantStreamEvent event : stream) {
                if (event instanceof TextDelta delta) {
                    text.append(delta.text());
                } else if (event instanceof AssistantDone done) {
                    usage = done.usage().orElse(ZERO_USAGE);
                } else if (event instanceof AssistantError error) {
                    if (CompactionPromptTooLongDetector.isPromptTooLong(error)) {
                        throw new PromptTooLongException(error);
                    }
                    throw new IllegalStateException("summary provider error: " + error.message());
                }
                if (request.abortSignal().aborted()) {
                    throw new SummaryAbortedException();
                }
            }
        } catch (RuntimeException exception) {
            if (exception instanceof PromptTooLongException || exception instanceof SummaryAbortedException) {
                throw exception;
            }
            if (CompactionPromptTooLongDetector.isPromptTooLong(exception)) {
                throw new PromptTooLongException(exception);
            }
            throw exception;
        }
        if (request.abortSignal().aborted()) {
            throw new SummaryAbortedException();
        }

        String summary = cleanSummary(text.toString());
        if (CompactionPromptTooLongDetector.isPromptTooLong(summary)) {
            throw new PromptTooLongException(summary);
        }
        if (summary.isBlank()) {
            throw new IllegalStateException("summary provider returned empty output");
        }
        return new CompactSummaryResult(summary, usage);
    }

    private CompactSummaryResult fallback(CompactSummaryRequest request, RuntimeException cause) {
        String policy = options.fallbackPolicy().name();
        throw new IllegalStateException("AI compaction summary failed with policy " + policy + ": " + cause.getMessage(), cause);
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

    private static final class PromptTooLongException extends IllegalStateException {
        private final RuntimeException causeException;
        private final AssistantError assistantError;
        private final String promptTooLongText;

        private PromptTooLongException(RuntimeException cause) {
            super(cause.getMessage(), cause);
            this.causeException = cause;
            this.assistantError = null;
            this.promptTooLongText = cause.getMessage();
        }

        private PromptTooLongException(AssistantError assistantError) {
            super(assistantError.message());
            this.causeException = null;
            this.assistantError = assistantError;
            this.promptTooLongText = assistantError.errorId() + " " + assistantError.message();
        }

        private PromptTooLongException(String promptTooLongText) {
            super(promptTooLongText);
            this.causeException = null;
            this.assistantError = null;
            this.promptTooLongText = promptTooLongText;
        }

        private Optional<List<AgentMessage>> truncate(List<AgentMessage> messages) {
            if (causeException != null) {
                return CompactionPromptTooLongRetry.truncateHeadForRetry(messages, causeException);
            }
            if (assistantError != null) {
                return CompactionPromptTooLongRetry.truncateHeadForRetry(messages, assistantError);
            }
            return CompactionPromptTooLongRetry.truncateHeadForRetry(messages, promptTooLongText);
        }
    }
}
