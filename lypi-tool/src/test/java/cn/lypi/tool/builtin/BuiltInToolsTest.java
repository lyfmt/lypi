package cn.lypi.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.runtime.ExecutionResult;
import cn.lypi.contracts.runtime.Executor;
import cn.lypi.contracts.runtime.AgentCenterPort;
import cn.lypi.contracts.runtime.AgentRegistryPort;
import cn.lypi.contracts.runtime.MailboxPort;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionDecisionReason;
import cn.lypi.contracts.security.PermissionUpdate;
import cn.lypi.contracts.subagent.AgentRunStatus;
import cn.lypi.contracts.subagent.AgentView;
import cn.lypi.contracts.subagent.HeadlessSubagentOutput;
import cn.lypi.contracts.subagent.MailboxCommandResult;
import cn.lypi.contracts.subagent.MailboxMessage;
import cn.lypi.contracts.subagent.MailboxStatus;
import cn.lypi.contracts.subagent.SubagentSpawnRequest;
import cn.lypi.contracts.subagent.SubagentSpawnResult;
import cn.lypi.contracts.tool.Tool;
import cn.lypi.tool.DefaultToolRuntime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class BuiltInToolsTest {
    @Test
    void createsDefaultToolSet() {
        List<Tool<?, ?>> tools = BuiltInTools.createDefaultTools(executor());

        Set<String> names = tools.stream().map(Tool::name).collect(Collectors.toSet());

        assertEquals(Set.of("read", "write", "edit", "bash", "grep", "glob"), names);
    }

    @Test
    void registersDefaultsIntoRuntime() {
        DefaultToolRuntime runtime = new DefaultToolRuntime((request, context) ->
            new PermissionDecision(
                PermissionBehavior.ALLOW,
                PermissionDecisionReason.TOOL_SPECIFIC,
                "allowed",
                Optional.<PermissionUpdate>empty(),
                Map.of()
            )
        );

        BuiltInTools.registerDefaults(runtime, executor());

        assertTrue(runtime.resolve("read").isPresent());
        assertTrue(runtime.resolve("bash").isPresent());
    }

    @Test
    void createsSubagentToolSetWithoutChangingDefaults() {
        List<Tool<?, ?>> defaultTools = BuiltInTools.createDefaultTools(executor());
        List<Tool<?, ?>> subagentTools = BuiltInTools.createSubagentTools(agentCenter(), mailbox());

        Set<String> defaultNames = defaultTools.stream().map(Tool::name).collect(Collectors.toSet());
        Set<String> subagentNames = subagentTools.stream().map(Tool::name).collect(Collectors.toSet());

        assertEquals(Set.of("read", "write", "edit", "bash", "grep", "glob"), defaultNames);
        assertEquals(Set.of(
            "spawn_agent",
            "interrupt_agent",
            "read_agent_result",
            "read_mailbox",
            "accept_mailbox_message"
        ), subagentNames);
    }

    @Test
    void registersSubagentToolsIntoRuntime() {
        DefaultToolRuntime runtime = new DefaultToolRuntime((request, context) ->
            new PermissionDecision(
                PermissionBehavior.ALLOW,
                PermissionDecisionReason.TOOL_SPECIFIC,
                "allowed",
                Optional.<PermissionUpdate>empty(),
                Map.of()
            )
        );

        BuiltInTools.registerSubagentTools(runtime, agentCenter(), mailbox());

        assertTrue(runtime.resolve("spawn_agent").isPresent());
        assertTrue(runtime.resolve("read_mailbox").isPresent());
        assertTrue(runtime.resolve("accept_mailbox_message").isPresent());
    }

    @Test
    void createsSubagentToolSetWithAgentRegistryTools() {
        List<Tool<?, ?>> subagentTools = BuiltInTools.createSubagentTools(agentCenter(), mailbox(), agentRegistry());

        Set<String> subagentNames = subagentTools.stream().map(Tool::name).collect(Collectors.toSet());

        assertEquals(Set.of(
            "spawn_agent",
            "interrupt_agent",
            "read_agent_result",
            "read_mailbox",
            "accept_mailbox_message",
            "list_agents"
        ), subagentNames);
    }

    private Executor executor() {
        return new Executor() {
            @Override
            public String name() {
                return "test";
            }

            @Override
            public ExecutionResult execute(
                cn.lypi.contracts.runtime.ExecutionRequest request,
                cn.lypi.contracts.common.ProgressSink progress,
                cn.lypi.contracts.common.AbortSignal signal
            ) {
                return new ExecutionResult(0, "", "", false, Optional.empty());
            }
        };
    }

    private AgentCenterPort agentCenter() {
        return new AgentCenterPort() {
            @Override
            public SubagentSpawnResult spawn(SubagentSpawnRequest request) {
                throw new UnsupportedOperationException("not used");
            }

            @Override
            public MailboxCommandResult interrupt(String agentId) {
                throw new UnsupportedOperationException("not used");
            }

            @Override
            public Optional<HeadlessSubagentOutput> readResult(String childSessionId) {
                throw new UnsupportedOperationException("not used");
            }
        };
    }

    private MailboxPort mailbox() {
        return new MailboxPort() {
            @Override
            public List<MailboxMessage> read(String sessionId, Set<MailboxStatus> statuses) {
                throw new UnsupportedOperationException("not used");
            }

            @Override
            public MailboxCommandResult accept(String sessionId, String mailId) {
                throw new UnsupportedOperationException("not used");
            }

            @Override
            public MailboxCommandResult stash(String sessionId, String mailId) {
                throw new UnsupportedOperationException("not used");
            }

            @Override
            public MailboxCommandResult discard(String sessionId, String mailId) {
                throw new UnsupportedOperationException("not used");
            }
        };
    }

    private AgentRegistryPort agentRegistry() {
        return new AgentRegistryPort() {
            @Override
            public List<AgentView> list(String parentSessionId, Set<AgentRunStatus> statuses) {
                return List.of();
            }
        };
    }
}
