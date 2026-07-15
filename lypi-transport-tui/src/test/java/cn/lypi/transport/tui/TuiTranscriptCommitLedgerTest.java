package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.lypi.contracts.tui.TuiMessageBlock;
import java.util.List;
import org.junit.jupiter.api.Test;

class TuiTranscriptCommitLedgerTest {
    @Test
    void returnsEachStableBlockOnlyOnceWithinProjection() {
        TuiProjectionKey key = new TuiProjectionKey("ses_1", "leaf_1");
        TuiTranscriptCommitLedger ledger = new TuiTranscriptCommitLedger();
        TuiMessageBlock user = message("user");
        TuiMessageBlock assistant = message("assistant");
        TuiMessageBlock tool = message("tool");

        assertEquals(List.of(user, assistant), ledger.advance(key, List.of(user, assistant)));
        assertEquals(List.of(), ledger.advance(key, List.of(user, assistant)));
        assertEquals(List.of(tool), ledger.advance(key, List.of(user, assistant, tool)));
    }

    @Test
    void projectionChangeStartsACommitEpoch() {
        TuiTranscriptCommitLedger ledger = new TuiTranscriptCommitLedger();
        TuiMessageBlock user = message("user");

        assertEquals(
            List.of(user),
            ledger.advance(new TuiProjectionKey("ses_1", "leaf_1"), List.of(user))
        );
        assertEquals(
            List.of(user),
            ledger.advance(new TuiProjectionKey("ses_2", "leaf_2"), List.of(user))
        );
    }

    @Test
    void stablePrefixRegressionDoesNotRecommitBlocksOrBlockLaterCommits() {
        TuiProjectionKey key = new TuiProjectionKey("ses_1", "leaf_1");
        TuiTranscriptCommitLedger ledger = new TuiTranscriptCommitLedger();
        TuiMessageBlock user = message("user");
        TuiMessageBlock assistant = message("assistant");
        TuiMessageBlock later = message("later");

        assertEquals(List.of(user, assistant), ledger.advance(key, List.of(user, assistant)));
        assertEquals(List.of(), ledger.advance(key, List.of(user)));
        assertEquals(List.of(), ledger.advance(key, List.of(user, assistant)));
        assertEquals(List.of(later), ledger.advance(key, List.of(user, assistant, later)));
    }

    private TuiMessageBlock message(String blockId) {
        return new TuiMessageBlock(blockId, "message-" + blockId, "assistant", blockId, false);
    }
}
