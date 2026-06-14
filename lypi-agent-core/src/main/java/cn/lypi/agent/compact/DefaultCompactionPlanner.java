package cn.lypi.agent.compact;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContentBlock;
import cn.lypi.contracts.context.ContextSnapshot;
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

        List<SessionEntry> candidates = branchEntries.subList(firstCandidateIndex, branchEntries.size());
        Optional<RoundCut> roundCut = firstKeptRound(candidates);
        if (roundCut.isEmpty()) {
            return Optional.empty();
        }

        int firstKeptIndex = firstCandidateIndex + roundCut.orElseThrow().firstKeptOffset();
        if (firstKeptIndex <= firstCandidateIndex) {
            return Optional.empty();
        }

        List<String> summarizedEntryIds = branchEntries.subList(firstCandidateIndex, firstKeptIndex).stream()
            .map(SessionEntry::id)
            .toList();
        if (summarizedEntryIds.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(new CompactionPlan(
            branchEntries.get(firstKeptIndex - 1).id(),
            branchEntries.get(firstKeptIndex).id(),
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

    private Optional<RoundCut> firstKeptRound(List<SessionEntry> candidates) {
        List<CompactionApiRound> rounds = CompactionApiRoundGrouper.groupEntries(candidates);
        if (rounds.size() < 2) {
            return Optional.empty();
        }

        int retainedTokens = 0;
        int firstKeptRoundIndex = rounds.size() - 1;

        for (int index = rounds.size() - 1; index >= 0; index--) {
            retainedTokens += estimateRoundTokens(rounds.get(index));
            firstKeptRoundIndex = index;
            if (retainedTokens >= keepRecentTokens) {
                break;
            }
        }

        if (firstKeptRoundIndex <= 0) {
            firstKeptRoundIndex = 1;
        }

        int firstKeptOffset = 0;
        for (int index = 0; index < firstKeptRoundIndex; index++) {
            firstKeptOffset += rounds.get(index).entries().size();
        }
        return Optional.of(new RoundCut(firstKeptOffset));
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

    private int estimateRoundTokens(CompactionApiRound round) {
        int tokens = 0;
        for (SessionEntry entry : round.entries()) {
            tokens += estimateEntryTokens(entry);
        }
        return tokens;
    }

    private int estimateBlockTokens(ContentBlock block) {
        String text = block.text() == null ? "" : block.text();
        return Math.max(1, text.length() / 4);
    }

    private record RoundCut(int firstKeptOffset) {}
}
