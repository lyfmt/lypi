package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.runtime.AgentCenterPort;
import cn.lypi.contracts.runtime.AgentRegistryPort;
import cn.lypi.contracts.subagent.AgentRunStatus;
import cn.lypi.contracts.subagent.AgentView;
import cn.lypi.contracts.subagent.MailboxCommandResult;
import cn.lypi.contracts.subagent.MailboxStatus;
import cn.lypi.contracts.tui.SlashCommand;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AgentSlashCommandHandlerTest {
    @Test
    void commandMetadataExposesAgentSlashCommand() {
        AgentSlashCommandHandler handler = new AgentSlashCommandHandler(new RecordingAgentRegistry(), () -> "ses_parent");

        SlashCommand command = handler.command();

        assertEquals("agent", command.name());
        assertTrue(command.description().contains("subagent"));
        assertTrue(command.parameters().stream().anyMatch(parameter -> "action".equals(parameter.name())));
        assertTrue(command.parameters().stream().anyMatch(parameter -> "statuses".equals(parameter.name())));
        assertEquals(handler, command.handler());
    }

    @Test
    void listReadsAgentsForCurrentSession() {
        RecordingAgentRegistry registry = new RecordingAgentRegistry();
        AgentSlashCommandHandler handler = new AgentSlashCommandHandler(registry, () -> "ses_parent");

        handler.handle(Map.of("action", "list", "statuses", "RUNNING"));

        assertEquals("ses_parent", registry.parentSessionId);
        assertEquals(Set.of(AgentRunStatus.RUNNING), registry.statuses);
        assertTrue(handler.lastOutput().contains("agent_1"));
        assertTrue(handler.lastOutput().contains("Scout [explorer]"));
        assertTrue(handler.lastOutput().contains("entry_spawn"));
        assertTrue(handler.lastOutput().contains("entry_final"));
    }

    @Test
    void invalidStatusFilterReturnsUserFacingError() {
        RecordingAgentRegistry registry = new RecordingAgentRegistry();
        AgentSlashCommandHandler handler = new AgentSlashCommandHandler(registry, () -> "ses_parent");

        handler.handle(Map.of("action", "list", "statuses", "missing"));

        assertTrue(handler.lastOutput().contains("未知 agent status"));
        assertEquals(null, registry.parentSessionId);
    }

    @Test
    void interruptSendsRequestToAgentCenter() {
        RecordingAgentCenter agentCenter = new RecordingAgentCenter();
        AgentSlashCommandHandler handler = new AgentSlashCommandHandler(
            new RecordingAgentRegistry(),
            agentCenter,
            () -> "ses_parent"
        );

        handler.handle(Map.of("action", "interrupt", "agentId", "agent_1"));

        assertEquals("agent_1", agentCenter.interruptedAgentId);
        assertTrue(handler.lastOutput().contains("中断请求已发送"));
        assertTrue(handler.lastOutput().contains("agent_1"));
    }

    @Test
    void interruptRequiresAgentId() {
        AgentSlashCommandHandler handler = new AgentSlashCommandHandler(
            new RecordingAgentRegistry(),
            new RecordingAgentCenter(),
            () -> "ses_parent"
        );

        handler.handle(Map.of("action", "interrupt"));

        assertTrue(handler.lastOutput().contains("agentId 不能为空"));
    }

    private static final class RecordingAgentRegistry implements AgentRegistryPort {
        private String parentSessionId;
        private Set<AgentRunStatus> statuses;

        @Override
        public List<AgentView> list(String parentSessionId, Set<AgentRunStatus> statuses) {
            this.parentSessionId = parentSessionId;
            this.statuses = statuses;
            return List.of(new AgentView(
                "agent_1",
                "Scout [explorer]",
                parentSessionId,
                "ses_child",
                "entry_spawn",
                AgentRunStatus.RUNNING,
                Optional.of(MailboxStatus.PENDING),
                Optional.of("完成摘要"),
                Optional.of("entry_final"),
                Optional.of("Scout"),
                Optional.of("explorer")
            ));
        }
    }

    private static final class RecordingAgentCenter implements AgentCenterPort {
        private String interruptedAgentId;

        @Override
        public cn.lypi.contracts.subagent.SubagentSpawnResult spawn(cn.lypi.contracts.subagent.SubagentSpawnRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public MailboxCommandResult interrupt(String agentId) {
            this.interruptedAgentId = agentId;
            return MailboxCommandResult.success(null);
        }

        @Override
        public cn.lypi.contracts.subagent.SubagentWaitResult waitFor(
            cn.lypi.contracts.subagent.SubagentWaitRequest request
        ) {
            return cn.lypi.contracts.subagent.SubagentWaitResult.timedOut();
        }
    }
}
