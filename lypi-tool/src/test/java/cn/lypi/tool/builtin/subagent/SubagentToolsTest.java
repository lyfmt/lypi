package cn.lypi.tool.builtin.subagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.runtime.AgentCenterPort;
import cn.lypi.contracts.runtime.AgentRegistryPort;
import cn.lypi.contracts.runtime.MailboxPort;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.subagent.AgentRunStatus;
import cn.lypi.contracts.subagent.AgentView;
import cn.lypi.contracts.subagent.HeadlessSubagentOutput;
import cn.lypi.contracts.subagent.MailboxCommandResult;
import cn.lypi.contracts.subagent.MailboxMessage;
import cn.lypi.contracts.subagent.MailboxStatus;
import cn.lypi.contracts.subagent.SubagentResultRef;
import cn.lypi.contracts.subagent.SubagentRunStatus;
import cn.lypi.contracts.subagent.SubagentSpawnRequest;
import cn.lypi.contracts.subagent.SubagentSpawnResult;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseContext;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SubagentToolsTest {
    @Test
    void spawnAgentStartsSubagentAndReturnsOnlyStartupStatus() {
        RecordingAgentCenter agentCenter = new RecordingAgentCenter();
        SpawnAgentTool tool = new SpawnAgentTool(agentCenter);

        ToolResult<String> result = tool.execute(Map.of(
            "prompt", "检查测试失败原因",
            "timeoutSeconds", 90,
            "agentName", "reviewer",
            "agentRole", "code-review"
        ), context(), ignored -> {
        });

        assertFalse(result.isError());
        assertTrue(result.output().contains("agent_1"));
        assertTrue(result.output().contains("STARTED"));
        assertFalse(result.output().contains("最终结果"));
        assertEquals("ses_parent", agentCenter.spawnRequest.parentSessionId());
        assertEquals("entry_tool_call", agentCenter.spawnRequest.parentEntryId());
        assertEquals(Path.of("/workspace"), agentCenter.spawnRequest.cwd());
        assertEquals(List.of(), agentCenter.spawnRequest.allowedTools());
        assertEquals(PermissionMode.DEFAULT_EXECUTE, agentCenter.spawnRequest.permissionMode());
        assertEquals(90, agentCenter.spawnRequest.timeoutSeconds());
        assertEquals(Optional.of("reviewer"), agentCenter.spawnRequest.agentName());
        assertEquals(Optional.of("code-review"), agentCenter.spawnRequest.agentRole());
        assertFalse(tool.isReadOnly(Map.of()));
    }

    @Test
    void spawnAgentSchemaExposesPlannedRoleAndAllowedToolsInputs() {
        SpawnAgentTool tool = new SpawnAgentTool(new RecordingAgentCenter());

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) tool.inputSchema().value().get("properties");

        assertTrue(properties.containsKey("role"));
        assertTrue(properties.containsKey("allowedTools"));
    }

    @Test
    void spawnAgentAcceptsRoleAliasAsAgentRole() {
        RecordingAgentCenter agentCenter = new RecordingAgentCenter();
        SpawnAgentTool tool = new SpawnAgentTool(agentCenter);

        ToolResult<String> result = tool.execute(Map.of(
            "prompt", "检查测试失败原因",
            "role", "code-review"
        ), context(), ignored -> {
        });

        assertFalse(result.isError());
        assertEquals(Optional.of("code-review"), agentCenter.spawnRequest.agentRole());
    }

    @Test
    void spawnAgentRejectsUnimplementedToolAndPermissionIsolationInputs() {
        RecordingAgentCenter agentCenter = new RecordingAgentCenter();
        SpawnAgentTool tool = new SpawnAgentTool(agentCenter);

        ToolResult<String> result = tool.execute(Map.of(
            "prompt", "检查测试失败原因",
            "allowedTools", List.of("read"),
            "permissionMode", "BYPASS"
        ), context(), ignored -> {
        });

        assertTrue(result.isError());
        assertTrue(result.output().contains("暂不支持"));
        assertEquals(null, agentCenter.spawnRequest);
    }

    @Test
    void spawnAgentReturnsToolErrorWhenAgentCenterCannotStart() {
        RecordingAgentCenter agentCenter = new RecordingAgentCenter();
        agentCenter.spawnResult = new SubagentSpawnResult(
            "",
            "",
            "ses_parent",
            "",
            SubagentRunStatus.FAILED,
            Optional.of("Subagent command is not configured")
        );
        SpawnAgentTool tool = new SpawnAgentTool(agentCenter);

        ToolResult<String> result = tool.execute(Map.of("prompt", "检查测试失败原因"), context(), ignored -> {
        });

        assertTrue(result.isError());
        assertTrue(result.output().contains("Subagent command is not configured"));
    }

    @Test
    void interruptAgentReturnsCommandResult() {
        RecordingAgentCenter agentCenter = new RecordingAgentCenter();
        InterruptAgentTool tool = new InterruptAgentTool(agentCenter);

        ToolResult<String> result = tool.execute(Map.of("agentId", "agent_1"), context(), ignored -> {
        });

        assertFalse(result.isError());
        assertEquals("agent_1", agentCenter.interruptedAgentId);
        assertTrue(result.output().contains("中断请求已发送"));
        assertFalse(tool.isReadOnly(Map.of()));
    }

    @Test
    void readAgentResultReadsFinalResultByChildSessionId() {
        RecordingAgentCenter agentCenter = new RecordingAgentCenter();
        ReadAgentResultTool tool = new ReadAgentResultTool(agentCenter);

        ToolResult<String> result = tool.execute(Map.of("childSessionId", "ses_child"), context(), ignored -> {
        });

        assertFalse(result.isError());
        assertEquals("ses_child", agentCenter.readResultChildSessionId);
        assertTrue(result.output().contains("完成摘要"));
        assertTrue(result.output().contains("entry_final"));
        assertTrue(tool.isReadOnly(Map.of()));
    }

    @Test
    void readMailboxReadsPendingMessagesForCurrentSession() {
        RecordingMailbox mailbox = new RecordingMailbox();
        ReadMailboxTool tool = new ReadMailboxTool(mailbox);

        ToolResult<String> result = tool.execute(Map.of("statuses", List.of("PENDING")), context(), ignored -> {
        });

        assertFalse(result.isError());
        assertEquals("ses_parent", mailbox.readSessionId);
        assertEquals(Set.of(MailboxStatus.PENDING), mailbox.readStatuses);
        assertTrue(result.output().contains("mail_1"));
        assertTrue(result.output().contains("子任务完成"));
        assertTrue(tool.isReadOnly(Map.of()));
    }

    @Test
    void acceptMailboxMessageDeliversMessageToCurrentSession() {
        RecordingMailbox mailbox = new RecordingMailbox();
        AcceptMailboxMessageTool tool = new AcceptMailboxMessageTool(mailbox);

        ToolResult<String> result = tool.execute(Map.of("mailId", "mail_1"), context(), ignored -> {
        });

        assertFalse(result.isError());
        assertEquals("ses_parent", mailbox.acceptSessionId);
        assertEquals("mail_1", mailbox.acceptMailId);
        assertTrue(result.output().contains("已接收 mailbox 消息"));
        assertFalse(tool.isReadOnly(Map.of()));
    }

    @Test
    void stashMailboxMessageUpdatesMessageStatusForCurrentSession() {
        RecordingMailbox mailbox = new RecordingMailbox();
        StashMailboxMessageTool tool = new StashMailboxMessageTool(mailbox);

        ToolResult<String> result = tool.execute(Map.of("mailId", "mail_1"), context(), ignored -> {
        });

        assertFalse(result.isError());
        assertEquals("ses_parent", mailbox.stashSessionId);
        assertEquals("mail_1", mailbox.stashMailId);
        assertTrue(result.output().contains("已暂存 mailbox 消息"));
        assertTrue(result.output().contains("STASHED"));
        assertFalse(tool.isReadOnly(Map.of()));
    }

    @Test
    void mailboxCommandSimpleSuccessMessageDoesNotKeepTrailingPunctuationBeforeMailId() {
        RecordingMailbox mailbox = new RecordingMailbox();
        mailbox.stashResult = MailboxCommandResult.success(null);
        StashMailboxMessageTool tool = new StashMailboxMessageTool(mailbox);

        ToolResult<String> result = tool.execute(Map.of("mailId", "mail_1"), context(), ignored -> {
        });

        assertFalse(result.isError());
        assertEquals("已暂存 mailbox 消息: mail_1", result.output());
    }

    @Test
    void discardMailboxMessageUpdatesMessageStatusForCurrentSession() {
        RecordingMailbox mailbox = new RecordingMailbox();
        DiscardMailboxMessageTool tool = new DiscardMailboxMessageTool(mailbox);

        ToolResult<String> result = tool.execute(Map.of("mailId", "mail_1"), context(), ignored -> {
        });

        assertFalse(result.isError());
        assertEquals("ses_parent", mailbox.discardSessionId);
        assertEquals("mail_1", mailbox.discardMailId);
        assertTrue(result.output().contains("已丢弃 mailbox 消息"));
        assertTrue(result.output().contains("DISCARDED"));
        assertFalse(tool.isReadOnly(Map.of()));
    }

    @Test
    void listAgentsReadsAgentViewsForCurrentSession() {
        RecordingAgentRegistry registry = new RecordingAgentRegistry();
        ListAgentsTool tool = new ListAgentsTool(registry);

        ToolResult<String> result = tool.execute(Map.of("statuses", List.of("RUNNING")), context(), ignored -> {
        });

        assertFalse(result.isError());
        assertEquals("ses_parent", registry.parentSessionId);
        assertEquals(Set.of(AgentRunStatus.RUNNING), registry.statuses);
        assertTrue(result.output().contains("agent_1"));
        assertTrue(result.output().contains("Scout [explorer]"));
        assertTrue(result.output().contains("ses_child"));
        assertTrue(result.output().contains("entry_final"));
        assertTrue(tool.isReadOnly(Map.of()));
    }

    @Test
    void listAgentsRejectsInvalidStatus() {
        ListAgentsTool tool = new ListAgentsTool(new RecordingAgentRegistry());

        ToolResult<String> result = tool.execute(Map.of("status", "UNKNOWN_STATUS"), context(), ignored -> {
        });

        assertTrue(result.isError());
        assertTrue(result.output().contains("未知 agent status"));
    }

    @Test
    void invalidRequiredInputReturnsValidationFailure() {
        RecordingAgentCenter agentCenter = new RecordingAgentCenter();

        assertFalse(new SpawnAgentTool(agentCenter).validateInput(Map.of(), context()).valid());
        assertFalse(new InterruptAgentTool(agentCenter).validateInput(Map.of(), context()).valid());
        assertFalse(new ReadAgentResultTool(agentCenter).validateInput(Map.of(), context()).valid());
        assertFalse(new AcceptMailboxMessageTool(new RecordingMailbox()).validateInput(Map.of(), context()).valid());
        assertFalse(new StashMailboxMessageTool(new RecordingMailbox()).validateInput(Map.of(), context()).valid());
        assertFalse(new DiscardMailboxMessageTool(new RecordingMailbox()).validateInput(Map.of(), context()).valid());
    }

    @Test
    void toolsExposeAllowPermissionMetadata() {
        RecordingAgentCenter agentCenter = new RecordingAgentCenter();

        assertEquals(PermissionBehavior.ALLOW, new SpawnAgentTool(agentCenter).checkPermissions(Map.of(), context()).behavior());
        assertEquals(PermissionBehavior.ALLOW, new ReadAgentResultTool(agentCenter).checkPermissions(Map.of(), context()).behavior());
        assertEquals(PermissionBehavior.ALLOW, new ReadMailboxTool(new RecordingMailbox()).checkPermissions(Map.of(), context()).behavior());
        assertEquals(PermissionBehavior.ALLOW, new ListAgentsTool(new RecordingAgentRegistry()).checkPermissions(Map.of(), context()).behavior());
    }

    private ToolUseContext context() {
        return new ToolUseContext(
            "ses_parent",
            "msg_parent",
            Path.of("/workspace"),
            Map.of(
                "toolUseId", "toolu_subagent",
                "permissionMode", PermissionMode.DEFAULT_EXECUTE,
                "parentEntryId", "entry_tool_call"
            )
        );
    }

    private MailboxMessage message(MailboxStatus status) {
        return new MailboxMessage(
            "mail_1",
            "agent_1",
            "ses_child",
            "ses_parent",
            "entry_spawn",
            "子任务完成",
            new SubagentResultRef("ses_child", "entry_final", Optional.empty()),
            status,
            Instant.EPOCH,
            Instant.EPOCH
        );
    }

    private final class RecordingAgentCenter implements AgentCenterPort {
        private SubagentSpawnRequest spawnRequest;
        private SubagentSpawnResult spawnResult;
        private String interruptedAgentId;
        private String readResultChildSessionId;

        @Override
        public SubagentSpawnResult spawn(SubagentSpawnRequest request) {
            this.spawnRequest = request;
            if (spawnResult != null) {
                return spawnResult;
            }
            return new SubagentSpawnResult(
                "agent_1",
                "ses_child",
                request.parentSessionId(),
                request.parentEntryId(),
                SubagentRunStatus.STARTED,
                Optional.of("started")
            );
        }

        @Override
        public MailboxCommandResult interrupt(String agentId) {
            this.interruptedAgentId = agentId;
            return MailboxCommandResult.success(null);
        }

        @Override
        public Optional<HeadlessSubagentOutput> readResult(String childSessionId) {
            this.readResultChildSessionId = childSessionId;
            return Optional.of(new HeadlessSubagentOutput(
                childSessionId,
                SubagentRunStatus.SUCCEEDED,
                "完成摘要",
                Optional.of("entry_final"),
                Optional.empty()
            ));
        }
    }

    private final class RecordingMailbox implements MailboxPort {
        private String readSessionId;
        private Set<MailboxStatus> readStatuses;
        private String acceptSessionId;
        private String acceptMailId;
        private String stashSessionId;
        private String stashMailId;
        private String discardSessionId;
        private String discardMailId;
        private MailboxCommandResult acceptResult = MailboxCommandResult.success(message(MailboxStatus.DELIVERED));
        private MailboxCommandResult stashResult = MailboxCommandResult.success(message(MailboxStatus.STASHED));
        private MailboxCommandResult discardResult = MailboxCommandResult.success(message(MailboxStatus.DISCARDED));

        @Override
        public List<MailboxMessage> read(String sessionId, Set<MailboxStatus> statuses) {
            this.readSessionId = sessionId;
            this.readStatuses = statuses;
            return List.of(message(MailboxStatus.PENDING));
        }

        @Override
        public MailboxCommandResult accept(String sessionId, String mailId) {
            this.acceptSessionId = sessionId;
            this.acceptMailId = mailId;
            return acceptResult;
        }

        @Override
        public MailboxCommandResult stash(String sessionId, String mailId) {
            this.stashSessionId = sessionId;
            this.stashMailId = mailId;
            return stashResult;
        }

        @Override
        public MailboxCommandResult discard(String sessionId, String mailId) {
            this.discardSessionId = sessionId;
            this.discardMailId = mailId;
            return discardResult;
        }
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
}
