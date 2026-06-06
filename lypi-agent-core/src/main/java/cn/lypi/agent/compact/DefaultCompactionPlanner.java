package cn.lypi.agent.compact;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContentBlock;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.session.CompactionEntry;
import cn.lypi.contracts.session.CompactionKind;
import cn.lypi.contracts.session.CompactionPlan;
import cn.lypi.contracts.session.MessageEntry;
import cn.lypi.contracts.session.SessionEntry;
import java.util.List;
import java.util.Optional;

public final class DefaultCompactionPlanner implements CompactionPlanner {
    private static final int DEFAULT_KEEP_RECENT_TOKENS = 20_000;

    private final int keepRecentTokens;

    public DefaultCompactionPlanner() {
        this(DEFAULT_KEEP_RECENT_TOKENS);
    }

    public DefaultCompactionPlanner(int keepRecentTokens) {
        this.keepRecentTokens = keepRecentTokens;
    }

    @Override
    public Optional<CompactionPlan> plan(List<SessionEntry> branchEntries, ContextSnapshot context) {
        if (!exceedsAutoCompactThreshold(context) || branchEntries.isEmpty()) {
            return Optional.empty();
        }

        if (branchEntries.getLast() instanceof CompactionEntry) {
            return Optional.empty();
        }

        int compactAfterIndex = lastCompactionIndex(branchEntries);
        int firstCandidateIndex = compactAfterIndex + 1;
        if (firstCandidateIndex >= branchEntries.size()) {
            return Optional.empty();
        }

        Optional<Integer> firstKeptIndex = firstKeptIndex(branchEntries, firstCandidateIndex);
        if (firstKeptIndex.isEmpty() || firstKeptIndex.orElseThrow() <= firstCandidateIndex) {
            return Optional.empty();
        }

        List<String> summarizedEntryIds = branchEntries.subList(firstCandidateIndex, firstKeptIndex.orElseThrow()).stream()
            .map(SessionEntry::id)
            .toList();
        if (summarizedEntryIds.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(new CompactionPlan(
            branchEntries.get(firstKeptIndex.orElseThrow() - 1).id(),
            branchEntries.get(firstKeptIndex.orElseThrow()).id(),
            summarizedEntryIds,
            CompactionKind.SESSION
        ));
    }

    private boolean exceedsAutoCompactThreshold(ContextSnapshot context) {
        return context.budget().estimatedContextTokens() > context.budget().autoCompactThreshold();
    }

    private int lastCompactionIndex(List<SessionEntry> branchEntries) {
        for (int index = branchEntries.size() - 1; index >= 0; index--) {
            if (branchEntries.get(index) instanceof CompactionEntry) {
                return index;
            }
        }
        return -1;
    }

    private Optional<Integer> firstKeptIndex(List<SessionEntry> branchEntries, int firstCandidateIndex) {
        int retainedTokens = 0;
        int candidateIndex = branchEntries.size() - 1;

        for (int index = branchEntries.size() - 1; index >= firstCandidateIndex; index--) {
            retainedTokens += estimateEntryTokens(branchEntries.get(index));
            candidateIndex = index;
            if (retainedTokens >= keepRecentTokens) {
                break;
            }
        }

        return nearestSafeBoundary(branchEntries, candidateIndex, firstCandidateIndex);
    }

    private Optional<Integer> nearestSafeBoundary(List<SessionEntry> branchEntries, int candidateIndex, int firstCandidateIndex) {
        for (int index = candidateIndex; index < branchEntries.size(); index++) {
            if (isSafeFirstKeptEntry(branchEntries.get(index))) {
                return Optional.of(index);
            }
        }

        for (int index = candidateIndex - 1; index >= firstCandidateIndex; index--) {
            if (isSafeFirstKeptEntry(branchEntries.get(index))) {
                return Optional.of(index);
            }
        }

        return Optional.empty();
    }

    private boolean isSafeFirstKeptEntry(SessionEntry entry) {
        if (!(entry instanceof MessageEntry messageEntry)) {
            return true;
        }
        return messageEntry.message().kind() != MessageKind.TOOL_RESULT;
    }

    private int estimateEntryTokens(SessionEntry entry) {
        if (!(entry instanceof MessageEntry messageEntry)) {
            return 1;
        }
        return estimateMessageTokens(messageEntry.message());
    }

    private int estimateMessageTokens(AgentMessage message) {
        return message.content().stream()
            .mapToInt(this::estimateBlockTokens)
            .sum();
    }

    private int estimateBlockTokens(ContentBlock block) {
        String text = block.text() == null ? "" : block.text();
        return Math.max(1, text.length() / 4);
    }
}
