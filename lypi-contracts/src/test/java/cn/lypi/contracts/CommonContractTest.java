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
import cn.lypi.contracts.common.AbortSignal;
import cn.lypi.contracts.common.IdGenerator;
import cn.lypi.contracts.common.ProgressSink;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.event.ToolProgressEvent;
import cn.lypi.contracts.model.AssistantEventStream;
import cn.lypi.contracts.model.AssistantStreamResult;
import cn.lypi.contracts.runtime.AgentCorePort;
import cn.lypi.contracts.runtime.AiProviderRuntimePort;
import cn.lypi.contracts.runtime.ResourceRuntimePort;
import cn.lypi.contracts.runtime.SecurityRuntimePort;
import cn.lypi.contracts.runtime.SessionEnginePort;
import cn.lypi.contracts.runtime.ToolRuntimePort;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import java.lang.reflect.Method;
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

    @Test
    void runtimePortsExposeDocumentedCrossModuleCapabilities() {
        assertAll(
            () -> assertMethod(SessionEnginePort.class, "openOrCreate", 1),
            () -> assertMethod(SessionEnginePort.class, "append", 1),
            () -> assertMethod(SessionEnginePort.class, "switchLeaf", 1),
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
            () -> assertMethod(AgentCorePort.class, "execute", 1),
            () -> assertMethod(ProgressSink.class, "progress", 1),
            () -> assertMethod(ToolProgressEvent.class, "progress", 0)
        );
    }

    @Test
    void aiProviderRuntimePortReturnsAssistantEventStream() throws Exception {
        assertEquals(
            AssistantEventStream.class,
            AiProviderRuntimePort.class.getMethod(
                "stream",
                ContextSnapshot.class,
                AbortSignal.class
            ).getReturnType()
        );
        assertTrue(AutoCloseable.class.isAssignableFrom(AssistantEventStream.class));
        assertTrue(Iterable.class.isAssignableFrom(AssistantEventStream.class));
        assertTrue(AssistantStreamResult.class.isRecord());
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
