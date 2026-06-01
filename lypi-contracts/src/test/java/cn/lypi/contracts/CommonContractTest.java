package cn.lypi.contracts;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.boundary.BoundaryCheckReport;
import cn.lypi.contracts.boundary.BoundaryRuleLevel;
import cn.lypi.contracts.boundary.BoundaryRuleResult;
import cn.lypi.contracts.boundary.FinalBoundaryRule;
import cn.lypi.contracts.common.IdGenerator;
import java.util.List;
import org.junit.jupiter.api.Test;

class CommonContractTest {
    @Test
    void generatedIdsUseDocumentedPrefixes() {
        IdGenerator generator = IdGenerator.random();

        assertAll(
            () -> assertTrue(generator.sessionId().startsWith("ses_")),
            () -> assertTrue(generator.entryId().startsWith("ent_")),
            () -> assertTrue(generator.turnId().startsWith("turn_")),
            () -> assertTrue(generator.messageId().startsWith("msg_")),
            () -> assertTrue(generator.toolUseId().startsWith("toolu_")),
            () -> assertTrue(generator.eventId().startsWith("evt_")),
            () -> assertTrue(generator.auditId().startsWith("aud_")),
            () -> assertTrue(generator.errorId().startsWith("err_"))
        );
    }

    @Test
    void boundaryReportDerivesPassStatusFromRuleResults() {
        FinalBoundaryRule rule = new FinalBoundaryRule(
            "session-append-only",
            "session adopts append-only JSONL",
            BoundaryRuleLevel.MUST,
            List.of("04", "20")
        );
        BoundaryRuleResult passed = new BoundaryRuleResult(rule.id(), true, "append API only");
        BoundaryRuleResult failed = new BoundaryRuleResult("mcp-server", false, "server capability claimed");

        assertEquals(true, new BoundaryCheckReport(List.of(passed)).passed());
        assertEquals(false, new BoundaryCheckReport(List.of(passed, failed)).passed());
    }
}
