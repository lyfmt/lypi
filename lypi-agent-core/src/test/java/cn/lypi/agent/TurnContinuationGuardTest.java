package cn.lypi.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TurnContinuationGuardTest {
    @Test
    void rejectsContinuationFromAssistantToolCallLeaf() {
        AgentCoreTestFixtures.InMemorySessionManager session = new AgentCoreTestFixtures.InMemorySessionManager();
        session.openOrCreate("session-1");
        session.appendMessage(AgentCoreTestFixtures.userMessage("msg-user", "read pom"));
        session.appendMessage(AgentCoreTestFixtures.assistantToolCallMessage(
            "msg-assistant",
            "toolu-1",
            "read",
            Map.of("path", "pom.xml")
        ));

        Optional<String> reason = new TurnContinuationGuard(session).unsafeContinuationReason(session.currentView().leafId());

        assertThat(reason).contains("cannot-continue-from-tool-call-assistant");
    }

    @Test
    void allowsContinuationFromToolResultLeaf() {
        AgentCoreTestFixtures.InMemorySessionManager session = new AgentCoreTestFixtures.InMemorySessionManager();
        session.openOrCreate("session-1");
        session.appendMessage(AgentCoreTestFixtures.userMessage("msg-user", "read pom"));
        session.appendMessage(AgentCoreTestFixtures.assistantToolCallMessage(
            "msg-assistant",
            "toolu-1",
            "read",
            Map.of("path", "pom.xml")
        ));
        session.appendMessage(AgentCoreTestFixtures.toolResultMessage("msg-tool", "toolu-1", "content", false));

        Optional<String> reason = new TurnContinuationGuard(session).unsafeContinuationReason(session.currentView().leafId());

        assertThat(reason).isEmpty();
    }
}
