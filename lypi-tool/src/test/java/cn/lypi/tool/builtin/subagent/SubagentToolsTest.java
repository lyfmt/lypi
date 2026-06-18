package cn.lypi.tool.builtin.subagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.runtime.AgentCenterPort;
import cn.lypi.contracts.runtime.AgentRegistryPort;
import cn.lypi.contracts.runtime.MailboxPort;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.ActivePermissionProfile;
import cn.lypi.contracts.security.ApprovalMode;
import cn.lypi.contracts.security.ApprovalPolicy;
import cn.lypi.contracts.security.LegacyPermissionBehavior;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.security.PermissionRuntimeState;
import cn.lypi.contracts.subagent.AgentRunStatus;
import cn.lypi.contracts.subagent.AgentView;
import cn.lypi.contracts.subagent.HeadlessSubagentOutput;
import cn.lypi.contracts.subagent.MailboxCommandResult;
import cn.lypi.contracts.subagent.MailboxMessage;
import cn.lypi.contracts.subagent.MailboxStatus;
import cn.lypi.contracts.subagent.SubagentResultRef;
import cn.lypi.contracts.subagent.SubagentRunStatus;
import cn.lypi.contracts.subagent.SubagentContinueRequest;
import cn.lypi.contracts.subagent.SubagentContinueResult;
import cn.lypi.contracts.subagent.SubagentSpawnRequest;
import cn.lypi.contracts.subagent.SubagentSpawnResult;
import cn.lypi.contracts.subagent.SubagentWaitRequest;
import cn.lypi.contracts.subagent.SubagentWaitResult;
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
        assertEquals(List.of("read", "grep", "glob"), agentCenter.spawnRequest.allowedTools());
        assertEquals(PermissionMode.DEFAULT_EXECUTE, agentCenter.spawnRequest.permissionMode());
        assertEquals(90, agentCenter.spawnRequest.timeoutSeconds());
        assertEquals(Optional.of("reviewer"), agentCenter.spawnRequest.agentName());
        assertEquals(Optional.of("code-review"), agentCenter.spawnRequest.agentRole());
        assertFalse(tool.isReadOnly(Map.of()));
    }

    @Test
    void spawnAgentDefaultsTimeoutToTwentyMinutesAndCapsExplicitTimeout() {
        RecordingAgentCenter agentCenter = new RecordingAgentCenter();
        SpawnAgentTool tool = new SpawnAgentTool(agentCenter);

        ToolResult<String> defaultResult = tool.execute(Map.of("prompt", "检查测试失败原因"), context(), ignored -> {
        });

        assertFalse(defaultResult.isError());
        assertEquals(1200, agentCenter.spawnRequest.timeoutSeconds());

        ToolResult<String> cappedResult = tool.execute(Map.of(
            "prompt", "检查测试失败原因",
            "timeoutSeconds", 3600
        ), context(), ignored -> {
        });

        assertFalse(cappedResult.isError());
        assertEquals(1200, agentCenter.spawnRequest.timeoutSeconds());
    }

    @Test
    void spawnAgentSchemaExposesPlannedRoleAndAllowedToolsInputs() {
        SpawnAgentTool tool = new SpawnAgentTool(new RecordingAgentCenter());

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) tool.inputSchema().value().get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> permissionMode = (Map<String, Object>) properties.get("permissionMode");
        @SuppressWarnings("unchecked")
        Map<String, Object> mode = (Map<String, Object>) properties.get("mode");
        @SuppressWarnings("unchecked")
        Map<String, Object> model = (Map<String, Object>) properties.get("model");
        @SuppressWarnings("unchecked")
        Map<String, Object> thinkingLevel = (Map<String, Object>) properties.get("thinkingLevel");
        @SuppressWarnings("unchecked")
        Map<String, Object> timeoutSeconds = (Map<String, Object>) properties.get("timeoutSeconds");

        assertTrue(properties.containsKey("role"));
        assertTrue(properties.containsKey("tools"));
        assertTrue(properties.containsKey("allowedTools"));
        assertTrue(properties.containsKey("model"));
        assertTrue(properties.containsKey("thinkingLevel"));
        assertTrue(properties.containsKey("mode"));
        assertTrue(properties.containsKey("permissionMode"));
        assertEquals(List.of("DEFAULT_EXECUTE", "ACCEPT_EDITS", "BYPASS"), permissionMode.get("enum"));
        assertTrue(permissionMode.get("description").toString().contains("useDefault"));
        assertEquals(1200, timeoutSeconds.get("maximum"));
        assertEquals(List.of("PLAN", "EXECUTE"), mode.get("enum"));
        assertTrue(mode.get("description").toString().contains("general"));
        assertTrue(model.get("description").toString().contains("继承父 session"));
        assertTrue(thinkingLevel.get("description").toString().contains("继承父 session"));
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
    void spawnAgentPassesExplicitToolsAndModelContext() {
        RecordingAgentCenter agentCenter = new RecordingAgentCenter();
        SpawnAgentTool tool = new SpawnAgentTool(agentCenter);

        ToolResult<String> result = tool.execute(Map.of(
            "prompt", "检查测试失败原因",
            "tools", List.of("read", "bash", "read"),
            "allowedTools", List.of("grep", "bash"),
            "model", "gpt-5.4",
            "thinkingLevel", "high",
            "mode", "plan",
            "permissionMode", "ACCEPT_EDITS"
        ), context(), ignored -> {
        });

        assertFalse(result.isError());
        assertEquals(List.of("read", "bash", "grep"), agentCenter.spawnRequest.toolPolicy().requestedTools());
        assertEquals(List.of("read", "grep", "glob", "bash"), agentCenter.spawnRequest.toolPolicy().effectiveTools());
        assertEquals(PermissionMode.ACCEPT_EDITS, agentCenter.spawnRequest.permissionMode());
        assertEquals(Optional.of(new ModelSelection("openai", "gpt-5.4", ThinkingLevel.HIGH)), agentCenter.spawnRequest.model());
        assertEquals(Optional.of(ThinkingLevel.HIGH), agentCenter.spawnRequest.thinkingLevel());
        assertEquals(Optional.of(AgentMode.PLAN), agentCenter.spawnRequest.agentMode());
    }

    @Test
    void spawnAgentPassesCanonicalPermissionRuntimeStateAndMarksItExplicit() {
        RecordingAgentCenter agentCenter = new RecordingAgentCenter();
        SpawnAgentTool tool = new SpawnAgentTool(agentCenter);
        PermissionRuntimeState runtimeState = customPermissionRuntimeState();

        ToolResult<String> result = tool.execute(Map.of(
            "prompt", "检查测试失败原因",
            "permissionRuntimeState", Map.of(
                "approvalPolicy", Map.of("mode", "UNLESS_TRUSTED"),
                "activePermissionProfile", Map.of("id", ":workspace-write"),
                "legacyBehavior", Map.of(
                    "defaultBashRequiresEscalation", false,
                    "allowExplicitEscalationWithoutPrompt", false,
                    "hardSafetyEnabled", false
                ),
                "legacyPermissionMode", "DEFAULT_EXECUTE"
            )
        ), context(), ignored -> {
        });

        assertFalse(result.isError());
        assertEquals(runtimeState, agentCenter.spawnRequest.permissionRuntimeState());
        assertTrue(agentCenter.spawnRequest.permissionModeSpecified());
    }

    @Test
    void spawnAgentKeepsExplicitProviderQualifiedModel() {
        RecordingAgentCenter agentCenter = new RecordingAgentCenter();
        SpawnAgentTool tool = new SpawnAgentTool(agentCenter);

        ToolResult<String> result = tool.execute(Map.of(
            "prompt", "检查测试失败原因",
            "model", "custom/gpt-5.4"
        ), context(), ignored -> {
        });

        assertFalse(result.isError());
        assertEquals(Optional.of(new ModelSelection("custom", "gpt-5.4", ThinkingLevel.MEDIUM)), agentCenter.spawnRequest.model());
    }

    @Test
    void spawnAgentNormalizesModelFriendlyPermissionAndModeAliases() {
        RecordingAgentCenter agentCenter = new RecordingAgentCenter();
        SpawnAgentTool tool = new SpawnAgentTool(agentCenter);

        ToolResult<String> result = tool.execute(Map.of(
            "prompt", "检查测试失败原因",
            "permissionMode", "useDefault",
            "mode", "general"
        ), context(), ignored -> {
        });

        assertFalse(result.isError());
        assertEquals(PermissionMode.DEFAULT_EXECUTE, agentCenter.spawnRequest.permissionMode());
        assertEquals(Optional.of(AgentMode.EXECUTE), agentCenter.spawnRequest.agentMode());
    }

    @Test
    void spawnAgentReturnsActionableErrorForUnknownPermissionMode() {
        SpawnAgentTool tool = new SpawnAgentTool(new RecordingAgentCenter());

        ToolResult<String> result = tool.execute(Map.of(
            "prompt", "检查测试失败原因",
            "permissionMode", "use-default-now"
        ), context(), ignored -> {
        });

        assertTrue(result.isError());
        assertTrue(result.output().contains("permissionMode"));
        assertTrue(result.output().contains("DEFAULT_EXECUTE"));
        assertFalse(result.output().contains("No enum constant"));
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
    void continueAgentStartsNextRunOnExistingChildSession() {
        RecordingAgentCenter agentCenter = new RecordingAgentCenter();
        ContinueAgentTool tool = new ContinueAgentTool(agentCenter);

        ToolResult<String> result = tool.execute(Map.of(
            "childSessionId", "ses_child",
            "prompt", "继续检查",
            "timeoutSeconds", 45
        ), context(), ignored -> {
        });

        assertFalse(result.isError());
        assertEquals("ses_parent", agentCenter.continueRequest.parentSessionId());
        assertEquals("entry_tool_call", agentCenter.continueRequest.parentEntryId());
        assertEquals("ses_child", agentCenter.continueRequest.childSessionId());
        assertEquals("继续检查", agentCenter.continueRequest.prompt());
        assertEquals(45, agentCenter.continueRequest.timeoutSeconds());
        assertTrue(result.output().contains("run_2"));
        assertTrue(result.output().contains("STARTED"));
        assertFalse(tool.isReadOnly(Map.of()));
    }

    @Test
    void continueAgentDefaultsTimeoutToTwentyMinutesAndCapsExplicitTimeout() {
        RecordingAgentCenter agentCenter = new RecordingAgentCenter();
        ContinueAgentTool tool = new ContinueAgentTool(agentCenter);

        ToolResult<String> defaultResult = tool.execute(Map.of(
            "childSessionId", "ses_child",
            "prompt", "继续检查"
        ), context(), ignored -> {
        });

        assertFalse(defaultResult.isError());
        assertEquals(1200, agentCenter.continueRequest.timeoutSeconds());

        ToolResult<String> cappedResult = tool.execute(Map.of(
            "childSessionId", "ses_child",
            "prompt", "继续检查",
            "timeoutSeconds", 3600
        ), context(), ignored -> {
        });

        assertFalse(cappedResult.isError());
        assertEquals(1200, agentCenter.continueRequest.timeoutSeconds());
    }

    @Test
    void continueAgentPassesExplicitToolsAndModelContext() {
        RecordingAgentCenter agentCenter = new RecordingAgentCenter();
        ContinueAgentTool tool = new ContinueAgentTool(agentCenter);

        ToolResult<String> result = tool.execute(Map.of(
            "childSessionId", "ses_child",
            "prompt", "继续检查",
            "tools", List.of("read", "bash", "read"),
            "allowedTools", List.of("grep", "bash"),
            "model", "gpt-5.4",
            "thinking", "high",
            "agentMode", "execute",
            "permission_mode", "ACCEPT_EDITS"
        ), context(), ignored -> {
        });

        assertFalse(result.isError());
        assertEquals(List.of("read", "bash", "grep"), agentCenter.continueRequest.toolPolicy().requestedTools());
        assertEquals(List.of("read", "grep", "glob", "bash"), agentCenter.continueRequest.toolPolicy().effectiveTools());
        assertEquals(PermissionMode.ACCEPT_EDITS, agentCenter.continueRequest.permissionMode());
        assertEquals(Optional.of(new ModelSelection("openai", "gpt-5.4", ThinkingLevel.HIGH)), agentCenter.continueRequest.model());
        assertEquals(Optional.of(ThinkingLevel.HIGH), agentCenter.continueRequest.thinkingLevel());
        assertEquals(Optional.of(AgentMode.EXECUTE), agentCenter.continueRequest.agentMode());
    }

    @Test
    void continueAgentOmitsPermissionOverrideWhenPermissionFieldsAreMissing() {
        RecordingAgentCenter agentCenter = new RecordingAgentCenter();
        ContinueAgentTool tool = new ContinueAgentTool(agentCenter);

        ToolResult<String> result = tool.execute(Map.of(
            "childSessionId", "ses_child",
            "prompt", "继续检查"
        ), context(), ignored -> {
        });

        assertFalse(result.isError());
        assertFalse(agentCenter.continueRequest.permissionRuntimeStateSpecified());
    }

    @Test
    void continueAgentPassesCanonicalPermissionRuntimeStateOnlyWhenExplicit() {
        RecordingAgentCenter agentCenter = new RecordingAgentCenter();
        ContinueAgentTool tool = new ContinueAgentTool(agentCenter);
        PermissionRuntimeState runtimeState = customPermissionRuntimeState();

        ToolResult<String> result = tool.execute(Map.of(
            "childSessionId", "ses_child",
            "prompt", "继续检查",
            "permissionRuntimeState", Map.of(
                "approvalPolicy", Map.of("mode", "UNLESS_TRUSTED"),
                "activePermissionProfile", Map.of("id", ":workspace-write"),
                "legacyBehavior", Map.of(
                    "defaultBashRequiresEscalation", false,
                    "allowExplicitEscalationWithoutPrompt", false,
                    "hardSafetyEnabled", false
                ),
                "legacyPermissionMode", "DEFAULT_EXECUTE"
            )
        ), context(), ignored -> {
        });

        assertFalse(result.isError());
        assertTrue(agentCenter.continueRequest.permissionRuntimeStateSpecified());
        assertEquals(runtimeState, agentCenter.continueRequest.permissionRuntimeState());
    }

    @Test
    void continueAgentNormalizesModelFriendlyPermissionAndModeAliases() {
        RecordingAgentCenter agentCenter = new RecordingAgentCenter();
        ContinueAgentTool tool = new ContinueAgentTool(agentCenter);

        ToolResult<String> result = tool.execute(Map.of(
            "childSessionId", "ses_child",
            "prompt", "继续检查",
            "permission_mode", "use_default",
            "agentMode", "general"
        ), context(), ignored -> {
        });

        assertFalse(result.isError());
        assertEquals(PermissionMode.DEFAULT_EXECUTE, agentCenter.continueRequest.permissionMode());
        assertEquals(Optional.of(AgentMode.EXECUTE), agentCenter.continueRequest.agentMode());
    }

    @Test
    void waitAgentReturnsSubagentCompletion() {
        RecordingAgentCenter agentCenter = new RecordingAgentCenter();
        WaitAgentTool tool = new WaitAgentTool(agentCenter);

        ToolResult<String> result = tool.execute(Map.of(
            "agentId", "agent_1",
            "timeoutSeconds", 30
        ), context(), ignored -> {
        });

        assertFalse(result.isError());
        assertEquals(Optional.of("agent_1"), agentCenter.waitRequest.agentId());
        assertEquals(30, agentCenter.waitRequest.timeoutSeconds());
        assertTrue(result.output().contains("SUCCEEDED"));
        assertTrue(result.output().contains("完成摘要"));
        assertTrue(result.output().contains("entry_final"));
        assertFalse(tool.isReadOnly(Map.of()));
    }

    @Test
    void waitAgentDefaultsTimeoutToTwentyMinutesAndCapsExplicitTimeout() {
        RecordingAgentCenter agentCenter = new RecordingAgentCenter();
        WaitAgentTool tool = new WaitAgentTool(agentCenter);

        ToolResult<String> defaultResult = tool.execute(Map.of("agentId", "agent_1"), context(), ignored -> {
        });

        assertFalse(defaultResult.isError());
        assertEquals(1200, agentCenter.waitRequest.timeoutSeconds());

        ToolResult<String> cappedResult = tool.execute(Map.of(
            "agentId", "agent_1",
            "timeoutSeconds", 3600
        ), context(), ignored -> {
        });

        assertFalse(cappedResult.isError());
        assertEquals(1200, agentCenter.waitRequest.timeoutSeconds());
    }

    @Test
    void waitAgentReturnsFailedRunAsReadableResultInsteadOfToolError() {
        RecordingAgentCenter agentCenter = new RecordingAgentCenter();
        agentCenter.waitResult = new SubagentWaitResult(
            "agent_1",
            "ses_child",
            "entry_spawn",
            SubagentRunStatus.FAILED,
            Optional.of("权限请求未获允许"),
            Optional.empty(),
            Optional.of("Child turn ended with FAILED: 权限请求未获允许")
        );
        WaitAgentTool tool = new WaitAgentTool(agentCenter);

        ToolResult<String> result = tool.execute(Map.of("childSessionId", "ses_child"), context(), ignored -> {
        });

        assertFalse(result.isError());
        assertTrue(result.output().contains("status: FAILED"));
        assertTrue(result.output().contains("权限请求未获允许"));
        assertTrue(result.output().contains("read_agent_result"));
    }

    @Test
    void waitAgentDescriptionTellsModelToWaitBeforeReadingAndNotFallback() {
        WaitAgentTool tool = new WaitAgentTool(new RecordingAgentCenter());

        assertTrue(tool.description().contains("read_agent_result"));
        assertTrue(tool.description().contains("不要改由父 Agent 自己完成"));
    }

    @Test
    void spawnAgentDescriptionWarnsBashNeedsNonInteractivePermission() {
        SpawnAgentTool tool = new SpawnAgentTool(new RecordingAgentCenter());

        assertTrue(tool.description().contains("只读调查"));
        assertTrue(tool.description().contains("不要默认加入 bash"));
        assertTrue(tool.description().contains("headless"));
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
        assertFalse(new ContinueAgentTool(agentCenter).validateInput(Map.of("childSessionId", "ses_child"), context()).valid());
        assertFalse(new WaitAgentTool(agentCenter).validateInput(Map.of(), context()).valid());
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
        assertEquals(PermissionBehavior.ALLOW, new ContinueAgentTool(agentCenter).checkPermissions(Map.of(), context()).behavior());
        assertEquals(PermissionBehavior.ALLOW, new WaitAgentTool(agentCenter).checkPermissions(Map.of(), context()).behavior());
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

    private PermissionRuntimeState customPermissionRuntimeState() {
        return new PermissionRuntimeState(
            new ApprovalPolicy(ApprovalMode.UNLESS_TRUSTED),
            new ActivePermissionProfile(":workspace-write"),
            new LegacyPermissionBehavior(false, false, false),
            PermissionMode.DEFAULT_EXECUTE
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
        private SubagentContinueRequest continueRequest;
        private SubagentContinueResult continueResult;
        private SubagentWaitRequest waitRequest;
        private SubagentWaitResult waitResult;
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
        public SubagentContinueResult continueRun(SubagentContinueRequest request) {
            this.continueRequest = request;
            if (continueResult != null) {
                return continueResult;
            }
            return new SubagentContinueResult(
                "agent_1",
                request.childSessionId(),
                request.parentSessionId(),
                "entry_continue",
                "run_2",
                SubagentRunStatus.STARTED,
                Optional.of("continued")
            );
        }

        @Override
        public SubagentWaitResult waitFor(SubagentWaitRequest request) {
            this.waitRequest = request;
            if (waitResult != null) {
                return waitResult;
            }
            return new SubagentWaitResult(
                request.agentId().orElse("agent_1"),
                request.childSessionId().orElse("ses_child"),
                request.runId().orElse("run_1"),
                SubagentRunStatus.SUCCEEDED,
                Optional.of("完成摘要"),
                Optional.of("entry_final"),
                Optional.empty()
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
