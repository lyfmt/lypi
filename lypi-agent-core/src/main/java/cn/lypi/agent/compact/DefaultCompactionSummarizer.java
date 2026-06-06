package cn.lypi.agent.compact;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContentBlock;
import cn.lypi.contracts.context.ToolCallContentBlock;
import cn.lypi.contracts.context.ToolResultContentBlock;
import cn.lypi.contracts.model.TokenUsage;
import cn.lypi.contracts.session.CompactionPlan;
import cn.lypi.contracts.session.MessageEntry;
import cn.lypi.contracts.session.SessionEntry;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class DefaultCompactionSummarizer implements CompactionSummarizer {
    private static final int MAX_BLOCK_PREVIEW_LENGTH = 1_000;
    private static final TokenUsage ZERO_USAGE = new TokenUsage(0, 0, 0, 0);

    @Override
    public CompactSummaryResult summarize(CompactSummaryRequest request) {
        List<SessionEntry> branchEntries = request.branchEntries();
        CompactionPlan plan = request.plan();
        List<MessageEntry> summarizedMessages = summarizedMessages(branchEntries, plan);
        String details = messageDetails(summarizedMessages);

        String summary = """
            ## Goal

            - Summarize prior session history so the agent can continue from the compacted context.

            ## Constraints & Preferences

            - Preserve user requests, tool outcomes, and decisions from the summarized range.

            ## Progress

            %s

            ## Key Decisions

            - No explicit decisions were inferred by the deterministic summarizer.

            ## Next Steps

            - Continue from the first kept entry: %s.

            ## Critical Context

            %s
            """.formatted(blankWhenEmpty(details), plan.firstKeptEntryId(), blankWhenEmpty(details)).strip();
        return new CompactSummaryResult(summary, ZERO_USAGE);
    }

    private List<MessageEntry> summarizedMessages(List<SessionEntry> branchEntries, CompactionPlan plan) {
        Set<String> summarizedEntryIds = new LinkedHashSet<>(plan.summarizedEntryIds());
        return branchEntries.stream()
            .filter(entry -> summarizedEntryIds.contains(entry.id()))
            .filter(MessageEntry.class::isInstance)
            .map(MessageEntry.class::cast)
            .toList();
    }

    private String messageDetails(List<MessageEntry> messages) {
        return messages.stream()
            .map(this::messageDetail)
            .reduce((left, right) -> left + "\n" + right)
            .orElse("");
    }

    private String messageDetail(MessageEntry entry) {
        AgentMessage message = entry.message();
        String content = message.content().stream()
            .map(this::blockPreview)
            .filter(preview -> !preview.isBlank())
            .reduce((left, right) -> left + " | " + right)
            .orElse("(empty)");

        return "- [%s] %s %s: %s".formatted(
            entry.id(),
            message.role(),
            message.kind(),
            content
        );
    }

    private String blockPreview(ContentBlock block) {
        if (block instanceof ToolCallContentBlock toolCall) {
            return "tool=%s toolUseId=%s input=%s".formatted(
                toolCall.toolName(),
                toolCall.toolUseId(),
                truncate(toolCall.text())
            );
        }
        if (block instanceof ToolResultContentBlock toolResult) {
            return "toolUseId=%s error=%s result=%s".formatted(
                toolResult.toolUseId(),
                toolResult.error(),
                truncate(toolResult.text())
            );
        }
        return truncate(block.text());
    }

    private String truncate(String text) {
        String safeText = text == null ? "" : text;
        if (safeText.length() <= MAX_BLOCK_PREVIEW_LENGTH) {
            return safeText;
        }
        return safeText.substring(0, MAX_BLOCK_PREVIEW_LENGTH) + "...";
    }

    private String blankWhenEmpty(String text) {
        return text.isBlank() ? "- No message details were available." : text;
    }
}
