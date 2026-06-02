package cn.lypi.contracts;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.boundary.BoundaryCheckReport;
import cn.lypi.contracts.boundary.BoundaryRuleLevel;
import cn.lypi.contracts.boundary.BoundaryRuleResult;
import cn.lypi.contracts.boundary.FinalBoundaryRule;
import cn.lypi.contracts.common.IdGenerator;
import cn.lypi.contracts.runtime.AgentCorePort;
import cn.lypi.contracts.runtime.AiProviderRuntimePort;
import cn.lypi.contracts.runtime.ResourceRuntimePort;
import cn.lypi.contracts.runtime.SecurityRuntimePort;
import cn.lypi.contracts.runtime.SessionEnginePort;
import cn.lypi.contracts.runtime.ToolRuntimePort;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;

class CommonContractTest {
    private final ObjectMapper mapper = new ObjectMapper();

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
    void runtimePortsExposeDocumentedCrossModuleCapabilities() {
        assertAll(
            () -> assertMethod(SessionEnginePort.class, "openOrCreate", 1),
            () -> assertMethod(SessionEnginePort.class, "append", 1),
            () -> assertMethod(SessionEnginePort.class, "pathToRoot", 1),
            () -> assertMethod(SessionEnginePort.class, "appendMessage", 1),
            () -> assertMethod(SessionEnginePort.class, "fork", 1),
            () -> assertMethod(AiProviderRuntimePort.class, "stream", 2),
            () -> assertMethod(ToolRuntimePort.class, "register", 1),
            () -> assertMethod(ToolRuntimePort.class, "resolve", 1),
            () -> assertMethod(ToolRuntimePort.class, "snapshot", 0),
            () -> assertMethod(ToolRuntimePort.class, "execute", 2),
            () -> assertMethod(SecurityRuntimePort.class, "decide", 2),
            () -> assertMethod(ResourceRuntimePort.class, "load", 1),
            () -> assertMethod(ResourceRuntimePort.class, "buildSystemPrompt", 1),
            () -> assertMethod(AgentCorePort.class, "execute", 1)
        );
    }

    private void assertMethod(Class<?> type, String name, int parameterCount) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == parameterCount) {
                return;
            }
        }
        throw new AssertionError(type.getSimpleName() + " is missing " + name + "/" + parameterCount);
    }
}
