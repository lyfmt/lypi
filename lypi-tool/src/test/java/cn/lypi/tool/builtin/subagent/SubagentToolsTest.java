package cn.lypi.tool.builtin.subagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.agent.SteeringMessageSource;
import cn.lypi.contracts.common.AbortSignal;
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
import cn.lypi.tool.ToolAbortSupport;
import cn.lypi.tool.ToolSteeringSupport;
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
    void spawnModelOverridesAreDocumentedAsOptionalInheritedValues() {
        SpawnAgentTool tool = new SpawnAgentTool(runtime(), new RecordingAgentCenter());
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) tool.inputSchema().value().get("properties");

        for (String field : List.of("provider", "model", "thinking_level")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> schema = (Map<String, Object>) properties.get(field);
            String description = String.valueOf(schema.get("description"));
            assertTrue(description.contains("省略"), field + " must explain omission");
            assertTrue(description.contains("空白"), field + " must explain blank-value tolerance");
            assertTrue(description.contains("继承"), field + " must explain inheritance");
        }
        assertEquals(List.of("task_name", "message"), tool.inputSchema().value().get("required"));
        assertTrue(tool.description().contains("默认继承"));
        assertTrue(tool.description().contains("可选参数"));
    }

    @Test
    void documentsAsynchronousCompletionAndNarrowWaitPolicy() {
        RecordingAgentCenter center = new RecordingAgentCenter();
        SpawnAgentTool spawn = new SpawnAgentTool(runtime(), center);
        WaitAgentTool wait = new WaitAgentTool(center);

        assertTrue(spawn.description().contains("自动投递"));
        assertTrue(spawn.description().contains("继续"));
        assertTrue(spawn.description().contains("不要调用 wait_agent"));
        assertTrue(wait.description().contains("阻塞"));
        assertTrue(wait.description().contains("自动投递"));
        assertTrue(wait.description().contains("没有其他可执行工作"));
        assertTrue(wait.description().contains("不要调用"));
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
        assertTrue(result.output().contains("自动投递"));
        assertTrue(result.output().contains("继续执行"));
        assertTrue(result.output().contains("仅当下一步依赖该结果"));
        assertTrue(result.output().contains("用户要求继续时不要等待"));
    }

    @Test
    void spawnTreatsBlankModelOverridesAsOmitted() {
        RecordingAgentCenter center = new RecordingAgentCenter();
        SpawnAgentTool tool = new SpawnAgentTool(runtime(), center);

        ToolResult<String> result = tool.execute(Map.of(
            "task_name", "inspect-tests",
            "message", "检查测试",
            "provider", "",
            "model", " ",
            "thinking_level", ""
        ), context(), ignored -> {
        });

        assertFalse(result.isError(), result.output());
        assertEquals(Optional.empty(), center.spawnRequest.provider());
        assertEquals(Optional.empty(), center.spawnRequest.model());
        assertEquals(Optional.empty(), center.spawnRequest.thinkingLevel());
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
        AbortSignal abort = () -> false;
        SteeringMessageSource steering = Optional::empty;

        ToolResult<String> result = tool.execute(Map.of("timeout_ms", 25_000), context(abort, steering), ignored -> {
        });
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) tool.inputSchema().value().get("properties");

        assertEquals(List.of("timeout_ms"), properties.keySet().stream().toList());
        assertEquals("ses_parent", center.waitRequest.parentSessionId());
        assertEquals(25_000, center.waitRequest.timeoutMillis());
        assertSame(abort, center.waitRequest.abortSignal());
        assertSame(steering, center.waitRequest.steeringMessages());
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

    @Test
    void waitRendersSteeringAndAbortOutcomesDistinctly() {
        RecordingAgentCenter center = new RecordingAgentCenter();
        WaitAgentTool tool = new WaitAgentTool(center);
        center.waitResult = SubagentWaitResult.steered();

        ToolResult<String> steered = tool.execute(Map.of("timeout_ms", 10), context(), ignored -> {
        });
        center.waitResult = SubagentWaitResult.aborted();
        ToolResult<String> aborted = tool.execute(Map.of("timeout_ms", 10), context(), ignored -> {
        });

        assertFalse(steered.isError());
        assertFalse(aborted.isError());
        assertTrue(steered.output().contains("新的用户输入"));
        assertTrue(aborted.output().contains("等待已中断"));
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
        return context(AbortSignal.none(), SteeringMessageSource.none());
    }

    private ToolUseContext context(AbortSignal abortSignal, SteeringMessageSource steeringMessages) {
        return new ToolUseContext(
            "ses_parent",
            "msg_1",
            Path.of("/workspace"),
            Map.of(
                "parentEntryId", "entry_tool_call",
                "toolUseId", "toolu_1",
                ToolAbortSupport.METADATA_ABORT_SIGNAL, abortSignal,
                ToolSteeringSupport.METADATA_STEERING_MESSAGES, steeringMessages
            )
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
