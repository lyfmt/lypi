package cn.lypi.tool.builtin.subagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.runtime.AgentCenterPort;
import cn.lypi.contracts.runtime.MailboxPort;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionMode;
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
            "allowedTools", List.of("read", "grep"),
            "permissionMode", "BYPASS",
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
        assertEquals("msg_parent", agentCenter.spawnRequest.parentEntryId());
        assertEquals(Path.of("/workspace"), agentCenter.spawnRequest.cwd());
        assertEquals(List.of("read", "grep"), agentCenter.spawnRequest.allowedTools());
        assertEquals(PermissionMode.BYPASS, agentCenter.spawnRequest.permissionMode());
        assertEquals(90, agentCenter.spawnRequest.timeoutSeconds());
        assertEquals(Optional.of("reviewer"), agentCenter.spawnRequest.agentName());
        assertEquals(Optional.of("code-review"), agentCenter.spawnRequest.agentRole());
        assertFalse(tool.isReadOnly(Map.of()));
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
    void invalidRequiredInputReturnsValidationFailure() {
        RecordingAgentCenter agentCenter = new RecordingAgentCenter();

        assertFalse(new SpawnAgentTool(agentCenter).validateInput(Map.of(), context()).valid());
        assertFalse(new InterruptAgentTool(agentCenter).validateInput(Map.of(), context()).valid());
        assertFalse(new ReadAgentResultTool(agentCenter).validateInput(Map.of(), context()).valid());
        assertFalse(new AcceptMailboxMessageTool(new RecordingMailbox()).validateInput(Map.of(), context()).valid());
    }

    @Test
    void toolsExposeAllowPermissionMetadata() {
        RecordingAgentCenter agentCenter = new RecordingAgentCenter();

        assertEquals(PermissionBehavior.ALLOW, new SpawnAgentTool(agentCenter).checkPermissions(Map.of(), context()).behavior());
        assertEquals(PermissionBehavior.ALLOW, new ReadAgentResultTool(agentCenter).checkPermissions(Map.of(), context()).behavior());
        assertEquals(PermissionBehavior.ALLOW, new ReadMailboxTool(new RecordingMailbox()).checkPermissions(Map.of(), context()).behavior());
    }

    private ToolUseContext context() {
        return new ToolUseContext(
            "ses_parent",
            "msg_parent",
            Path.of("/workspace"),
            Map.of("toolUseId", "toolu_subagent", "permissionMode", PermissionMode.DEFAULT_EXECUTE)
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
        private String interruptedAgentId;
        private String readResultChildSessionId;

        @Override
        public SubagentSpawnResult spawn(SubagentSpawnRequest request) {
            this.spawnRequest = request;
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
            return MailboxCommandResult.success(message(MailboxStatus.DELIVERED));
        }

        @Override
        public MailboxCommandResult stash(String sessionId, String mailId) {
            return MailboxCommandResult.success(message(MailboxStatus.STASHED));
        }

        @Override
        public MailboxCommandResult discard(String sessionId, String mailId) {
            return MailboxCommandResult.success(message(MailboxStatus.DISCARDED));
        }
    }
}
