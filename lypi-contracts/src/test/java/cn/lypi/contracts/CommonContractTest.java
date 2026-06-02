package cn.lypi.contracts;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.boundary.BoundaryCheckReport;
import cn.lypi.contracts.boundary.BoundaryRuleLevel;
import cn.lypi.contracts.boundary.BoundaryRuleResult;
import cn.lypi.contracts.boundary.CapabilityGuardResult;
import cn.lypi.contracts.boundary.ExcludedCapability;
import cn.lypi.contracts.boundary.ExclusionKind;
import cn.lypi.contracts.boundary.FinalBoundaryRule;
import cn.lypi.contracts.common.IdGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CommonContractTest {
    private final ObjectMapper mapper = new ObjectMapper()
        .registerModule(new Jdk8Module());

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

    @Test
    void boundaryReportIgnoresInputPassStatusAndDerivesItFromResults() throws Exception {
        String json = """
            {
              "results": [
                {
                  "ruleId": "session-append-only",
                  "passed": true,
                  "evidence": "append API only"
                }
              ],
              "passed": false
            }
            """;

        BoundaryCheckReport restored = mapper.readValue(json, BoundaryCheckReport.class);

        assertTrue(restored.passed());
        assertTrue(mapper.writeValueAsString(restored).contains("\"passed\":true"));
    }

    @Test
    void excludedCapabilityContractsPreserveReservedInterfaceAndGuardDecision() throws Exception {
        ExcludedCapability capability = new ExcludedCapability(
            "MCP Server",
            "ly-pi only consumes MCP tools as a client",
            ExclusionKind.NOT_SUPPORTED,
            Optional.empty()
        );
        ExcludedCapability sandbox = new ExcludedCapability(
            "Docker sandbox",
            "sandbox implementations are reserved interfaces only",
            ExclusionKind.INTERFACE_RESERVED_ONLY,
            Optional.of("Executor")
        );
        CapabilityGuardResult guardResult = new CapabilityGuardResult(
            sandbox.name(),
            false,
            "Docker sandbox cannot be registered as an available runtime capability"
        );

        String capabilityJson = mapper.writeValueAsString(capability);
        String sandboxJson = mapper.writeValueAsString(sandbox);
        String guardJson = mapper.writeValueAsString(guardResult);

        assertAll(
            () -> assertTrue(capabilityJson.contains("\"kind\":\"NOT_SUPPORTED\"")),
            () -> assertTrue(sandboxJson.contains("\"reservedInterface\":\"Executor\"")),
            () -> assertTrue(guardJson.contains("\"allowed\":false"))
        );

        ExcludedCapability restoredCapability = mapper.readValue(capabilityJson, ExcludedCapability.class);
        ExcludedCapability restoredSandbox = mapper.readValue(sandboxJson, ExcludedCapability.class);
        CapabilityGuardResult restoredGuardResult = mapper.readValue(guardJson, CapabilityGuardResult.class);

        assertAll(
            () -> assertEquals(ExclusionKind.NOT_SUPPORTED, restoredCapability.kind()),
            () -> assertTrue(restoredCapability.reservedInterface().isEmpty()),
            () -> assertEquals(Optional.of("Executor"), restoredSandbox.reservedInterface()),
            () -> assertEquals("Docker sandbox", restoredGuardResult.capability()),
            () -> assertEquals(false, restoredGuardResult.allowed())
        );
    }
}
