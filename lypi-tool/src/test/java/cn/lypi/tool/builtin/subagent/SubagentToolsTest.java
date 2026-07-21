package cn.lypi.tool.builtin.subagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.common.ValidationResult;
import cn.lypi.contracts.runtime.AgentCenterPort;
import cn.lypi.contracts.runtime.ExecutionRequest;
import cn.lypi.contracts.runtime.ExecutionResult;
import cn.lypi.contracts.runtime.Executor;
import cn.lypi.contracts.subagent.MailboxCommandResult;
import cn.lypi.contracts.subagent.SubagentRunStatus;
import cn.lypi.contracts.subagent.SubagentSpawnRequest;
import cn.lypi.contracts.subagent.SubagentSpawnResult;
import cn.lypi.contracts.subagent.SubagentWaitRequest;
import cn.lypi.contracts.subagent.SubagentWaitResult;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseContext;
import cn.lypi.tool.DefaultToolRuntime;
import cn.lypi.tool.builtin.BuiltInTools;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SubagentToolsTest {
    @Test
    void spawnSchemaContainsOnlyFinalCanonicalParameters() {
        SpawnAgentTool tool = new SpawnAgentTool(runtime(), new RecordingAgentCenter());

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) tool.inputSchema().value().get("properties");

        assertEquals(
            List.of("message", "model", "provider", "task_name", "thinking_level", "tools"),
            properties.keySet().stream().sorted().toList()
        );
        assertEquals(List.of("task_name", "message"), tool.inputSchema().value().get("required"));
        assertEquals(false, tool.inputSchema().value().get("additionalProperties"));
    }

    @Test
    void spawnPassesCanonicalEffectiveToolsAndIndependentModelOptions() {
        RecordingAgentCenter center = new RecordingAgentCenter();
        SpawnAgentTool tool = new SpawnAgentTool(runtime(), center);

        ToolResult<String> result = tool.execute(Map.of(
            "task_name", "inspect-tests",
            "message", "检查测试失败原因",
            "tools", List.of("bash", "bash"),
            "provider", "openai",
            "model", "gpt-5.4",
            "thinking_level", "HIGH"
        ), context(), ignored -> {
        });

        assertFalse(result.isError());
        assertEquals("inspect-tests", center.spawnRequest.taskName());
        assertEquals("检查测试失败原因", center.spawnRequest.message());
        assertEquals(List.of("read", "grep", "glob", "bash"), center.spawnRequest.tools());
        assertEquals(Optional.of("openai"), center.spawnRequest.provider());
        assertEquals(Optional.of("gpt-5.4"), center.spawnRequest.model());
        assertTrue(result.output().contains("run_1"));
        assertTrue(result.output().contains("inspect-tests"));
    }

    @Test
    void spawnRejectsAliasesUnknownToolsAndRemovedFields() {
        SpawnAgentTool tool = new SpawnAgentTool(runtime(), new RecordingAgentCenter());

        ToolResult<String> alias = tool.execute(Map.of(
            "task_name", "inspect",
            "message", "检查",
            "tools", List.of("read_file")
        ), context(), ignored -> {
        });
        ToolResult<String> unknown = tool.execute(Map.of(
            "task_name", "inspect",
            "message", "检查",
            "tools", List.of("missing_tool")
        ), context(), ignored -> {
        });
        ValidationResult removedField = tool.validateInput(Map.of(
            "task_name", "inspect",
            "message", "检查",
            "cwd", "/tmp"
        ), context());

        assertTrue(alias.isError());
        assertTrue(unknown.isError());
        assertTrue(alias.output().contains("canonical") || alias.output().contains("不存在"));
        assertTrue(unknown.output().contains("不存在"));
        assertFalse(removedField.valid());
    }

    @Test
    void waitSchemaOnlyAcceptsTimeoutMillisAndReturnsCompletionDirectly() {
        RecordingAgentCenter center = new RecordingAgentCenter();
        center.waitResult = SubagentWaitResult.completed(
            "inspect-tests",
            "agent_1",
            "ses_child",
            "run_1",
            SubagentRunStatus.SUCCEEDED,
            "检查完成"
        );
        WaitAgentTool tool = new WaitAgentTool(center);

        ToolResult<String> result = tool.execute(Map.of("timeout_ms", 25_000), context(), ignored -> {
        });
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) tool.inputSchema().value().get("properties");

        assertEquals(List.of("timeout_ms"), properties.keySet().stream().toList());
        assertEquals("ses_parent", center.waitRequest.parentSessionId());
        assertEquals(25_000, center.waitRequest.timeoutMillis());
        assertTrue(result.output().contains("inspect-tests"));
        assertTrue(result.output().contains("agent_1"));
        assertTrue(result.output().contains("run_1"));
        assertTrue(result.output().contains("检查完成"));
    }

    @Test
    void waitTimeoutDoesNotReportChildTimedOut() {
        RecordingAgentCenter center = new RecordingAgentCenter();
        center.waitResult = SubagentWaitResult.timedOut();
        WaitAgentTool tool = new WaitAgentTool(center);

        ToolResult<String> result = tool.execute(Map.of("timeout_ms", 10), context(), ignored -> {
        });

        assertFalse(result.isError());
        assertTrue(result.output().contains("尚未回复"));
        assertFalse(result.output().contains("TIMED_OUT"));
    }

    private DefaultToolRuntime runtime() {
        DefaultToolRuntime runtime = new DefaultToolRuntime((request, context) ->
            new cn.lypi.contracts.security.PermissionDecision(
                cn.lypi.contracts.security.PermissionBehavior.ALLOW,
                cn.lypi.contracts.security.PermissionDecisionReason.TOOL_SPECIFIC,
                "allowed",
                Optional.empty(),
                Map.of()
            )
        );
        BuiltInTools.registerDefaults(runtime, executor());
        return runtime;
    }

    private Executor executor() {
        return new Executor() {
            @Override
            public String name() {
                return "test";
            }

            @Override
            public ExecutionResult execute(
                ExecutionRequest request,
                cn.lypi.contracts.common.ProgressSink progress,
                cn.lypi.contracts.common.AbortSignal signal
            ) {
                return new ExecutionResult(0, "", "", false, Optional.empty());
            }
        };
    }

    private ToolUseContext context() {
        return new ToolUseContext(
            "ses_parent",
            "msg_1",
            Path.of("/workspace"),
            Map.of("parentEntryId", "entry_tool_call", "toolUseId", "toolu_1")
        );
    }

    private static final class RecordingAgentCenter implements AgentCenterPort {
        private SubagentSpawnRequest spawnRequest;
        private SubagentWaitRequest waitRequest;
        private SubagentWaitResult waitResult = SubagentWaitResult.timedOut();

        @Override
        public SubagentSpawnResult spawn(SubagentSpawnRequest request) {
            spawnRequest = request;
            return new SubagentSpawnResult(
                request.taskName(),
                "agent_1",
                "ses_child",
                "run_1",
                SubagentRunStatus.STARTED,
                Optional.of("started")
            );
        }

        @Override
        public SubagentWaitResult waitFor(SubagentWaitRequest request) {
            waitRequest = request;
            return waitResult;
        }

        @Override
        public MailboxCommandResult interrupt(String agentId) {
            return MailboxCommandResult.failure("not used");
        }
    }
}
