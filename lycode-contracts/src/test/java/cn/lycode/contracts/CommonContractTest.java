package cn.lycode.contracts;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lycode.contracts.boundary.BoundaryCheckReport;
import cn.lycode.contracts.boundary.BoundaryRuleLevel;
import cn.lycode.contracts.boundary.BoundaryRuleResult;
import cn.lycode.contracts.boundary.CapabilityGuardResult;
import cn.lycode.contracts.boundary.ExcludedCapability;
import cn.lycode.contracts.boundary.ExclusionKind;
import cn.lycode.contracts.boundary.FinalBoundaryRule;
import cn.lycode.contracts.common.AbortSignal;
import cn.lycode.contracts.common.IdGenerator;
import cn.lycode.contracts.common.ProgressSink;
import cn.lycode.contracts.context.ContextSnapshot;
import cn.lycode.contracts.event.ToolProgressEvent;
import cn.lycode.contracts.model.AssistantEventStream;
import cn.lycode.contracts.model.AssistantStreamResult;
import cn.lycode.contracts.runtime.AgentCenterPort;
import cn.lycode.contracts.runtime.AgentCommunicationPort;
import cn.lycode.contracts.runtime.AgentCorePort;
import cn.lycode.contracts.runtime.AiProviderRuntimePort;
import cn.lycode.contracts.runtime.ChildSessionPort;
import cn.lycode.contracts.runtime.ResourceRuntimePort;
import cn.lycode.contracts.runtime.SecurityRuntimePort;
import cn.lycode.contracts.runtime.SessionManagerFactoryPort;
import cn.lycode.contracts.runtime.SessionManagerPort;
import cn.lycode.contracts.runtime.SessionStorageRootPort;
import cn.lycode.contracts.runtime.ToolRuntimePort;
import cn.lycode.contracts.runtime.ProviderLoginPort;
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
            () -> assertTrue(generator.entryId().startsWith("entry_")),
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
            "ly-code only consumes MCP tools as a client",
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
            () -> assertMethod(SessionManagerPort.class, "openOrCreate", 1),
            () -> assertMethod(SessionManagerPort.class, "append", 1),
            () -> assertMethod(SessionManagerPort.class, "switchLeaf", 1),
            () -> assertMethod(SessionManagerPort.class, "branch", 1),
            () -> assertMethod(SessionManagerPort.class, "currentView", 0),
            () -> assertMethod(SessionManagerPort.class, "view", 1),
            () -> assertMethod(SessionManagerPort.class, "transcript", 1),
            () -> assertMethod(SessionManagerPort.class, "context", 1),
            () -> assertMethod(SessionManagerPort.class, "appendMessage", 1),
            () -> assertMethod(SessionManagerPort.class, "fork", 1),
            () -> assertMethod(AiProviderRuntimePort.class, "stream", 2),
            () -> assertMethod(ToolRuntimePort.class, "register", 1),
            () -> assertMethod(ToolRuntimePort.class, "resolve", 1),
            () -> assertMethod(ToolRuntimePort.class, "snapshot", 0),
            () -> assertMethod(ToolRuntimePort.class, "cwd", 0),
            () -> assertMethod(ToolRuntimePort.class, "execute", 2),
            () -> assertMethod(ToolRuntimePort.class, "execute", 3),
            () -> assertMethod(SecurityRuntimePort.class, "decide", 2),
            () -> assertMethod(ResourceRuntimePort.class, "load", 1),
            () -> assertMethod(ResourceRuntimePort.class, "buildSystemPrompt", 1),
            () -> assertMethod(ResourceRuntimePort.class, "buildSystemPrompt", 2),
            () -> assertMethod(AgentCorePort.class, "execute", 1),
            () -> assertMethod(AgentCenterPort.class, "spawn", 1),
            () -> assertMethod(AgentCenterPort.class, "waitFor", 1),
            () -> assertMethod(AgentCommunicationPort.class, "poll", 1),
            () -> assertMethod(ChildSessionPort.class, "create", 1),
            () -> assertMethod(SessionManagerFactoryPort.class, "open", 2),
            () -> assertMethod(SessionStorageRootPort.class, "sessionStorageRoot", 0),
            () -> assertMethod(ProviderLoginPort.class, "register", 3),
            () -> assertMethod(ProgressSink.class, "progress", 1),
            () -> assertMethod(ToolProgressEvent.class, "progress", 0)
        );
    }

    @Test
    void unavailableProviderLoginUsesTheNamedChannelContract() {
        IllegalStateException error = assertThrows(
            IllegalStateException.class,
            () -> ProviderLoginPort.unavailable().register("zen", "https://example.test/v1", "test-key")
        );

        assertEquals("provider login is unavailable", error.getMessage());
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
