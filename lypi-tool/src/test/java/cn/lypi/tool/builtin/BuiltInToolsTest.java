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
import cn.lypi.tool.web.WebProviderRegistry;
import cn.lypi.tool.web.WebResultStore;
import cn.lypi.tool.web.WebSearchProvider;
import cn.lypi.contracts.web.WebSearchResponse;
import cn.lypi.tool.DefaultToolRuntime;
import cn.lypi.tool.web.WebStoredResult;
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

        assertEquals(Set.of("read", "write", "edit", "request_permissions", "bash", "grep", "glob"), names);
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
        assertTrue(runtime.resolve("request_permissions").isPresent());
        assertTrue(runtime.resolve("bash").isPresent());
    }

    @Test
    void createsSubagentToolSetWithoutChangingDefaults() {
        List<Tool<?, ?>> defaultTools = BuiltInTools.createDefaultTools(executor());
        List<Tool<?, ?>> subagentTools = BuiltInTools.createSubagentTools(agentCenter(), mailbox());

        Set<String> defaultNames = defaultTools.stream().map(Tool::name).collect(Collectors.toSet());
        Set<String> subagentNames = subagentTools.stream().map(Tool::name).collect(Collectors.toSet());

        assertEquals(Set.of("read", "write", "edit", "request_permissions", "bash", "grep", "glob"), defaultNames);
        assertEquals(Set.of(
            "spawn_agent",
            "continue_agent",
            "wait_agent",
            "interrupt_agent",
            "read_agent_result",
            "read_mailbox",
            "accept_mailbox_message",
            "stash_mailbox_message",
            "discard_mailbox_message"
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
        assertTrue(runtime.resolve("continue_agent").isPresent());
        assertTrue(runtime.resolve("wait_agent").isPresent());
        assertTrue(runtime.resolve("read_mailbox").isPresent());
        assertTrue(runtime.resolve("accept_mailbox_message").isPresent());
        assertTrue(runtime.resolve("stash_mailbox_message").isPresent());
        assertTrue(runtime.resolve("discard_mailbox_message").isPresent());
    }

    @Test
    void registersWebToolsOnce() {
        DefaultToolRuntime runtime = new DefaultToolRuntime((request, context) ->
            new PermissionDecision(
                PermissionBehavior.ALLOW,
                PermissionDecisionReason.TOOL_SPECIFIC,
                "allowed",
                Optional.<PermissionUpdate>empty(),
                Map.of()
            )
        );

        BuiltInTools.registerWebTools(
            runtime,
            new WebProviderRegistry("test", Map.of("test", searchProvider())),
            WebResultStore.noop()
        );

        assertTrue(runtime.resolve("web_search").isPresent());
        assertTrue(runtime.resolve("web_fetch").isPresent());
        assertTrue(runtime.resolve("get_search_content").isPresent());
    }

    @Test
    void registersWebContentTool() {
        DefaultToolRuntime runtime = new DefaultToolRuntime((request, context) ->
            new PermissionDecision(
                PermissionBehavior.ALLOW,
                PermissionDecisionReason.TOOL_SPECIFIC,
                "allowed",
                Optional.<PermissionUpdate>empty(),
                Map.of()
            )
        );

        BuiltInTools.registerWebContentTools(runtime, WebResultStore.noop());

        assertTrue(runtime.resolve("get_search_content").isPresent());
    }

    @Test
    void registeredWebSearchUsesProvidedResultStore() {
        DefaultToolRuntime runtime = new DefaultToolRuntime((request, context) ->
            new PermissionDecision(
                PermissionBehavior.ALLOW,
                PermissionDecisionReason.TOOL_SPECIFIC,
                "allowed",
                Optional.<PermissionUpdate>empty(),
                Map.of()
            )
        );
        RecordingWebResultStore store = new RecordingWebResultStore();

        BuiltInTools.registerWebTools(
            runtime,
            new WebProviderRegistry("test", Map.of("test", searchProvider())),
            store
        );
        @SuppressWarnings("unchecked")
        Tool<Map<String, Object>, String> tool = (Tool<Map<String, Object>, String>) runtime.resolve("web_search").orElseThrow();

        tool.execute(
            Map.of("query", "java"),
            new cn.lypi.contracts.tool.ToolUseContext("session", "message", java.nio.file.Path.of("."), Map.of("toolUseId", "toolu_1")),
            progress -> {
            }
        );

        assertTrue(store.wasSaved());
    }

    @Test
    void createsSubagentToolSetWithAgentRegistryTools() {
        List<Tool<?, ?>> subagentTools = BuiltInTools.createSubagentTools(agentCenter(), mailbox(), agentRegistry());

        Set<String> subagentNames = subagentTools.stream().map(Tool::name).collect(Collectors.toSet());

        assertEquals(Set.of(
            "spawn_agent",
            "continue_agent",
            "wait_agent",
            "interrupt_agent",
            "read_agent_result",
            "read_mailbox",
            "accept_mailbox_message",
            "stash_mailbox_message",
            "discard_mailbox_message",
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

    private WebSearchProvider searchProvider() {
        return new WebSearchProvider() {
            @Override
            public String name() {
                return "test";
            }

            @Override
            public WebSearchResponse search(cn.lypi.tool.web.WebSearchRequest request) {
                return new WebSearchResponse("test", request.query(), Optional.empty(), List.of(), Optional.empty());
            }
        };
    }

    private static final class RecordingWebResultStore implements WebResultStore {
        private WebStoredResult saved;

        @Override
        public WebStoredResult save(WebStoredResult result) {
            saved = new WebStoredResult(
                result.sessionId(),
                result.messageId(),
                "web_1",
                result.sourceTool(),
                result.query(),
                result.url(),
                result.items(),
                result.createdAt()
            );
            return saved;
        }

        @Override
        public Optional<WebStoredResult> findByResponseId(String sessionId, String responseId) {
            return Optional.empty();
        }

        @Override
        public Optional<WebStoredResult> findLatestByQuery(String sessionId, String query) {
            return Optional.empty();
        }

        private boolean wasSaved() {
            return saved != null;
        }
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
