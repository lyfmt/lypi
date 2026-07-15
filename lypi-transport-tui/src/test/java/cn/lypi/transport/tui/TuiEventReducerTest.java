package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.common.ToolProgress;
import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContentBlockKind;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.TextContentBlock;
import cn.lypi.contracts.context.ToolCallContentBlock;
import cn.lypi.contracts.context.ToolResultContentBlock;
import cn.lypi.contracts.event.CompactEndEvent;
import cn.lypi.contracts.event.CompactStartEvent;
import cn.lypi.contracts.event.ErrorEvent;
import cn.lypi.contracts.event.InterruptEvent;
import cn.lypi.contracts.event.MessageBlockSnapshot;
import cn.lypi.contracts.event.MessageDeltaEvent;
import cn.lypi.contracts.event.MessageEndEvent;
import cn.lypi.contracts.event.MessageStartEvent;
import cn.lypi.contracts.event.MemoryWriteEvent;
import cn.lypi.contracts.event.PermissionDecisionEvent;
import cn.lypi.contracts.event.PermissionRequestEvent;
import cn.lypi.contracts.event.PermissionResponseEvent;
import cn.lypi.contracts.event.ProviderFallbackEndEvent;
import cn.lypi.contracts.event.ProviderFallbackStartEvent;
import cn.lypi.contracts.event.RetryEndEvent;
import cn.lypi.contracts.event.RetryStartEvent;
import cn.lypi.contracts.event.SessionStartEvent;
import cn.lypi.contracts.event.SessionStateEvent;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.security.ActivePermissionProfile;
import cn.lypi.contracts.security.AdditionalPermissionProfile;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.ApprovalKind;
import cn.lypi.contracts.security.ApprovalMode;
import cn.lypi.contracts.security.ApprovalPolicy;
import cn.lypi.contracts.security.FileSystemPermissionPolicy;
import cn.lypi.contracts.security.NetworkPermissionPolicy;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.event.ToolEndEvent;
import cn.lypi.contracts.event.ToolProgressEvent;
import cn.lypi.contracts.event.ToolStartEvent;
import cn.lypi.contracts.event.TurnEndEvent;
import cn.lypi.contracts.event.TurnStartEvent;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionDecisionReason;
import cn.lypi.contracts.security.PermissionOption;
import cn.lypi.contracts.security.PermissionOptionKind;
import cn.lypi.contracts.security.PermissionRuntimeState;
import cn.lypi.contracts.security.ReviewDecision;
import cn.lypi.contracts.session.SessionView;
import cn.lypi.contracts.tool.ToolExecutionStatus;
import cn.lypi.contracts.tool.ToolOutputRef;
import cn.lypi.contracts.tool.ToolResultSummary;
import cn.lypi.contracts.tui.StatusBarState;
import cn.lypi.contracts.tui.SessionRuntimeState;
import cn.lypi.contracts.tui.TuiBlock;
import cn.lypi.contracts.tui.TuiBlockKind;
import cn.lypi.contracts.tui.TuiErrorBlock;
import cn.lypi.contracts.tui.TuiMessageBlock;
import cn.lypi.contracts.tui.TuiThinkingBlock;
import cn.lypi.contracts.tui.TuiToolBlock;
import cn.lypi.contracts.tui.TuiToolState;
import cn.lypi.contracts.tui.TuiViewModel;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TuiEventReducerTest {
    private static final Instant NOW = Instant.parse("2026-06-07T09:00:00Z");

    @Test
    void messageEndSnapshotCreatesUserAndNonStreamingAssistantBlocks() {
        TuiEventReducer reducer = new TuiEventReducer();

        reducer.reduce(new MessageStartEvent("ses_1", "msg_user", MessageRole.USER, MessageKind.TEXT, Map.of(), NOW));
        reducer.reduce(new MessageEndEvent(
            "ses_1",
            "msg_user",
            MessageRole.USER,
            MessageKind.TEXT,
            List.of(new MessageBlockSnapshot("msg_user:text:0", ContentBlockKind.TEXT, "请修复 TUI", Map.of())),
            Optional.empty(),
            Optional.empty(),
            Map.of(),
            NOW
        ));
        reducer.reduce(new MessageEndEvent(
            "ses_1",
            "msg_assistant",
            MessageRole.ASSISTANT,
            MessageKind.TEXT,
            List.of(new MessageBlockSnapshot("msg_assistant:text:0", ContentBlockKind.TEXT, "已收到", Map.of())),
            Optional.empty(),
            Optional.of("stop"),
            Map.of(),
            NOW
        ));

        TuiMessageBlock user = assertInstanceOf(TuiMessageBlock.class, reducer.view().blocks().get(0));
        TuiMessageBlock assistant = assertInstanceOf(TuiMessageBlock.class, reducer.view().blocks().get(1));
        assertEquals("user", user.role());
        assertEquals("请修复 TUI", user.content());
        assertFalse(user.streaming());
        assertEquals("assistant", assistant.role());
        assertEquals("已收到", assistant.content());
        assertFalse(assistant.streaming());
    }

    @Test
    void messageEndSnapshotCreatesThinkingErrorAndToolCallBlocksWhenNoDeltaArrived() {
        TuiEventReducer reducer = new TuiEventReducer();

        reducer.reduce(new MessageEndEvent(
            "ses_1",
            "msg_1",
            MessageRole.ASSISTANT,
            MessageKind.ERROR,
            List.of(
                new MessageBlockSnapshot("think_1", ContentBlockKind.THINKING, "分析中", Map.of()),
                new MessageBlockSnapshot("err_1", ContentBlockKind.ERROR, "provider failed", Map.of()),
                new MessageBlockSnapshot("tool_1", ContentBlockKind.TOOL_CALL, "", Map.of(
                    "toolUseId", "toolu_1",
                    "toolName", "bash",
                    "inputSummary", "mvn test"
                ))
            ),
            Optional.empty(),
            Optional.of("error"),
            Map.of(),
            NOW
        ));

        TuiThinkingBlock thinking = assertInstanceOf(TuiThinkingBlock.class, reducer.view().blocks().get(0));
        TuiErrorBlock error = assertInstanceOf(TuiErrorBlock.class, reducer.view().blocks().get(1));
        TuiToolBlock tool = assertInstanceOf(TuiToolBlock.class, reducer.view().blocks().get(2));
        assertEquals("分析中", thinking.content());
        assertFalse(thinking.streaming());
        assertEquals("provider failed", error.message());
        assertEquals("toolu_1", tool.toolUseId());
        assertEquals("bash", tool.toolName());
        assertEquals("mvn test", tool.label());
        assertFalse(tool.active());
    }

    @Test
    void messageEndSnapshotDoesNotDuplicateDeltaBlockContent() {
        TuiEventReducer reducer = new TuiEventReducer();

        reducer.reduce(new MessageDeltaEvent(
            "ses_1",
            "msg_1",
            MessageRole.ASSISTANT,
            MessageKind.TEXT,
            "block_1",
            ContentBlockKind.TEXT,
            "hello",
            false,
            Map.of(),
            NOW
        ));
        reducer.reduce(new MessageEndEvent(
            "ses_1",
            "msg_1",
            MessageRole.ASSISTANT,
            MessageKind.TEXT,
            List.of(new MessageBlockSnapshot("block_1", ContentBlockKind.TEXT, "hello", Map.of())),
            Optional.empty(),
            Optional.of("stop"),
            Map.of(),
            NOW
        ));

        assertEquals(1, reducer.view().blocks().size());
        TuiMessageBlock message = assertInstanceOf(TuiMessageBlock.class, reducer.view().blocks().getFirst());
        assertEquals("hello", message.content());
        assertFalse(message.streaming());
    }

    @Test
    void messageEndSnapshotReplacesEmptyStartPlaceholderWhenBlockIdDiffers() {
        TuiEventReducer reducer = new TuiEventReducer();

        reducer.reduce(new MessageStartEvent("ses_1", "msg_1", MessageRole.ASSISTANT, MessageKind.TEXT, Map.of(), NOW));
        reducer.reduce(new MessageEndEvent(
            "ses_1",
            "msg_1",
            MessageRole.ASSISTANT,
            MessageKind.TEXT,
            List.of(new MessageBlockSnapshot("block_1", ContentBlockKind.TEXT, "hello", Map.of())),
            Optional.empty(),
            Optional.of("stop"),
            Map.of(),
            NOW
        ));

        assertEquals(1, reducer.view().blocks().size());
        TuiMessageBlock message = assertInstanceOf(TuiMessageBlock.class, reducer.view().blocks().getFirst());
        assertEquals("block_1", message.blockId());
        assertEquals("hello", message.content());
        assertFalse(message.streaming());
    }

    @Test
    void nonProvisionalAssistantMessageStartCreatesPlaceholder() {
        TuiEventReducer reducer = new TuiEventReducer();

        reducer.reduce(new MessageStartEvent("ses_1", "msg_1", MessageRole.ASSISTANT, MessageKind.TEXT, Map.of(), NOW));

        TuiMessageBlock message = assertInstanceOf(TuiMessageBlock.class, reducer.view().blocks().getFirst());
        assertEquals("msg_1:text:0", message.blockId());
        assertEquals("assistant", message.role());
        assertEquals("", message.content());
        assertTrue(message.streaming());
    }

    @Test
    void provisionalAssistantMessageStartWaitsForDeltaOrSnapshotBlockKind() {
        TuiEventReducer reducer = new TuiEventReducer();

        reducer.reduce(new MessageStartEvent(
            "ses_1",
            "msg_1",
            MessageRole.ASSISTANT,
            MessageKind.TOOL_CALL,
            Map.of("kindProvisional", true),
            NOW
        ));

        assertEquals(0, reducer.view().blocks().size());
    }

    @Test
    void toolProgressAndEndPopulateRenderableDetails() {
        TuiEventReducer reducer = new TuiEventReducer();

        reducer.reduce(new ToolStartEvent(
            "ses_1",
            "toolu_1",
            "msg_1",
            "turn_1",
            "bash",
            "Bash",
            "mvn test",
            Map.of("command", "mvn test"),
            NOW,
            NOW
        ));
        reducer.reduce(new ToolProgressEvent("ses_1", "toolu_1", ToolProgress.output("stdout", "line 1\n"), NOW));
        reducer.reduce(new ToolProgressEvent("ses_1", "toolu_1", ToolProgress.output("stderr", "warn\n"), NOW));
        reducer.reduce(new ToolProgressEvent("ses_1", "toolu_1", ToolProgress.percent("tests", 50.0), NOW));
        reducer.reduce(new ToolEndEvent(
            "ses_1",
            "toolu_1",
            ToolExecutionStatus.SUCCEEDED,
            0,
            new ToolResultSummary("bash succeeded", "10 tests passed", false, 0, false, 42L, Map.of("preview", "BUILD SUCCESS")),
            new ToolOutputRef("ref_1", "ses_1", "toolu_1", "text/plain", "file", "target/out.log", "sha256:1", 42L, Map.of()),
            NOW,
            NOW.plusMillis(20),
            20L,
            Map.of(),
            NOW.plusMillis(20)
        ));

        TuiToolBlock tool = assertInstanceOf(TuiToolBlock.class, reducer.view().blocks().getFirst());
        assertEquals(TuiToolState.DONE, tool.state());
        assertFalse(tool.active());
        assertTrue(tool.details().contains("stdout: line 1"));
        assertTrue(tool.details().contains("stderr: warn"));
        assertTrue(tool.details().contains("tests 50%"));
        assertTrue(tool.details().contains("exit 0"));
        assertTrue(tool.details().contains("10 tests passed"));
        assertTrue(tool.details().contains("BUILD SUCCESS"));
    }

    @Test
    void toolProgressRetainsBoundedTailAndFinalSummary() {
        TuiEventReducer reducer = new TuiEventReducer();
        reducer.reduce(new ToolStartEvent(
            "ses_1",
            "toolu_1",
            "msg_1",
            "turn_1",
            "bash",
            "Bash",
            "large output",
            Map.of(),
            NOW,
            NOW
        ));
        for (int index = 0; index < 300; index++) {
            String line = "line-%03d %s\n".formatted(index, "x".repeat(1014));
            reducer.reduce(new ToolProgressEvent(
                "ses_1",
                "toolu_1",
                ToolProgress.output("stdout", line),
                NOW.plusMillis(index)
            ));
        }
        reducer.reduce(new ToolEndEvent(
            "ses_1",
            "toolu_1",
            ToolExecutionStatus.SUCCEEDED,
            0,
            new ToolResultSummary("bash succeeded", "all output captured", false, 0, false, 307_200L, Map.of()),
            null,
            NOW,
            NOW.plusSeconds(1),
            1_000L,
            Map.of(),
            NOW.plusSeconds(1)
        ));

        TuiToolBlock tool = assertInstanceOf(TuiToolBlock.class, reducer.view().blocks().getFirst());
        assertTrue(tool.details().length() <= 17 * 1024);
        assertTrue(tool.details().contains("line-299"));
        assertFalse(tool.details().contains("line-000"));
        assertTrue(tool.details().contains("earlier output omitted"));
        assertTrue(tool.details().contains("exit 0"));
        assertTrue(tool.details().contains("all output captured"));
        assertFalse(tool.active());
    }

    @Test
    void toolLifecyclePreservesInputSummaryMetadataPreviewAndResultSummary() {
        TuiEventReducer reducer = new TuiEventReducer();

        reducer.reduce(new ToolStartEvent(
            "ses_1",
            "toolu_1",
            "msg_1",
            "turn_1",
            "read",
            "Read",
            "src/App.java:1-80",
            Map.of("preview", "1 | class App {}", "path", "src/App.java"),
            NOW,
            NOW
        ));
        reducer.reduce(new ToolEndEvent(
            "ses_1",
            "toolu_1",
            ToolExecutionStatus.SUCCEEDED,
            null,
            new ToolResultSummary("read succeeded", "1 line", false, 0, false, 18L, Map.of("preview", "2 | end")),
            null,
            NOW,
            NOW.plusMillis(4),
            4L,
            Map.of(),
            NOW.plusMillis(4)
        ));

        TuiToolBlock tool = assertInstanceOf(TuiToolBlock.class, reducer.view().blocks().getFirst());
        assertEquals("read", tool.toolName());
        assertEquals("src/App.java:1-80", tool.label());
        assertEquals(TuiToolState.DONE, tool.state());
        assertTrue(tool.details().contains("1 | class App {}"));
        assertTrue(tool.details().contains("1 line"));
        assertTrue(tool.details().contains("2 | end"));
    }

    @Test
    void toolEndUsesOutputRefPreviewBeforeSummaryMetadataPreview() {
        TuiEventReducer reducer = new TuiEventReducer();

        reducer.reduce(new ToolStartEvent(
            "ses_1",
            "toolu_1",
            "msg_1",
            "turn_1",
            "bash",
            "Bash",
            "mvn test",
            Map.of(),
            NOW,
            NOW
        ));
        reducer.reduce(new ToolEndEvent(
            "ses_1",
            "toolu_1",
            ToolExecutionStatus.SUCCEEDED,
            0,
            new ToolResultSummary("bash succeeded", "10 tests passed", false, 0, false, 42L, Map.of("preview", "summary preview")),
            new ToolOutputRef(
                "ref_1",
                "ses_1",
                "toolu_1",
                "text/plain",
                "pending",
                "",
                "sha256:1",
                42L,
                Map.of("preview", "ref preview")
            ),
            NOW,
            NOW.plusMillis(20),
            20L,
            Map.of(),
            NOW.plusMillis(20)
        ));

        TuiToolBlock tool = assertInstanceOf(TuiToolBlock.class, reducer.view().blocks().getFirst());
        assertTrue(tool.details().contains("ref preview"));
        assertTrue(!tool.details().contains("summary preview"));
    }

    @Test
    void toolEndMapsFailureTimeoutAndCancellationStates() {
        assertToolEndState(ToolExecutionStatus.FAILED, TuiToolState.FAILED);
        assertToolEndState(ToolExecutionStatus.TIMED_OUT, TuiToolState.FAILED);
        assertToolEndState(ToolExecutionStatus.CANCELLED, TuiToolState.CANCELLED);
    }

    @Test
    void runtimeEventsUpdateEphemeralRuntimeLineWithoutAddingBlocks() {
        TuiEventReducer reducer = TuiEventReducer.withRuntimeState(TestRuntimeStates.basic("ses_1"));

        reducer.reduce(new TurnStartEvent("ses_1", "turn_1", NOW));
        assertEquals("working (0s)", reducer.view().runtimeLine());
        assertEquals(0, reducer.view().blocks().size());

        reducer.reduce(new RetryStartEvent("ses_1", 2, "rate limit", NOW));
        assertEquals("retrying attempt 2 rate limit", reducer.view().runtimeLine());

        reducer.reduce(new RetryEndEvent("ses_1", 2, true, NOW));
        assertEquals("working (0s)", reducer.view().runtimeLine());

        reducer.reduce(new CompactStartEvent("ses_1", "session", NOW));
        assertEquals("compacting session", reducer.view().runtimeLine());

        reducer.reduce(new CompactEndEvent("ses_1", "compact_1", NOW));
        assertEquals("working (0s)", reducer.view().runtimeLine());

        reducer.reduce(new InterruptEvent("ses_1", "user cancelled", NOW));
        assertEquals("interrupted user cancelled", reducer.view().runtimeLine());

        reducer.reduce(new TurnEndEvent("ses_1", "turn_1", "completed", NOW));
        assertEquals("worked 0s", reducer.view().runtimeLine());
        assertEquals("EXECUTE", reducer.view().statusBar().mode());
        assertEquals(0, reducer.view().blocks().size());
    }

    @Test
    void providerFallbackEventsUpdateEphemeralRuntimeLineWithExistingPriorities() {
        TuiEventReducer reducer = TuiEventReducer.withRuntimeState(TestRuntimeStates.basic("ses_1"));
        reducer.reduce(new TurnStartEvent("ses_1", "turn_1", NOW));

        reducer.reduce(new ProviderFallbackStartEvent(
            "ses_1",
            "responses/websocket",
            "responses/sse",
            "provider.fallback_candidate",
            NOW
        ));
        assertEquals(
            "fallback responses/websocket -> responses/sse provider.fallback_candidate",
            reducer.view().runtimeLine()
        );
        assertEquals(0, reducer.view().blocks().size());

        reducer.reduce(new CompactStartEvent("ses_1", "session", NOW));
        assertEquals("compacting session", reducer.view().runtimeLine());
        reducer.reduce(new CompactEndEvent("ses_1", "compact_1", NOW));
        assertEquals(
            "fallback responses/websocket -> responses/sse provider.fallback_candidate",
            reducer.view().runtimeLine()
        );

        reducer.reduce(new RetryStartEvent("ses_1", 2, "rate limit", NOW));
        assertEquals("retrying attempt 2 rate limit", reducer.view().runtimeLine());
        reducer.reduce(new RetryEndEvent("ses_1", 2, true, NOW));
        assertEquals(
            "fallback responses/websocket -> responses/sse provider.fallback_candidate",
            reducer.view().runtimeLine()
        );

        reducer.reduce(new ProviderFallbackEndEvent("ses_1", "responses/sse", true, NOW));
        assertEquals("working (0s)", reducer.view().runtimeLine());
    }

    @Test
    void failedProviderFallbackLineIsReplacedByErrorAndClearedByRuntimeBoundaries() {
        TuiEventReducer reducer = TuiEventReducer.withRuntimeState(TestRuntimeStates.basic("ses_1"));
        reducer.reduce(new TurnStartEvent("ses_1", "turn_1", NOW));
        ProviderFallbackStartEvent start = new ProviderFallbackStartEvent(
            "ses_1",
            "responses/websocket",
            "responses/sse",
            "provider.fallback_candidate",
            NOW
        );

        reducer.reduce(start);
        reducer.reduce(new ProviderFallbackEndEvent("ses_1", "responses/sse", false, NOW));
        assertEquals("fallback failed responses/sse", reducer.view().runtimeLine());

        reducer.reduce(new ErrorEvent("ses_1", "provider.request_failed", "request failed", NOW));
        assertEquals("working (0s)", reducer.view().runtimeLine());

        reducer.reduce(start);
        reducer.reduce(new InterruptEvent("ses_1", "esc", NOW));
        assertEquals("interrupted esc", reducer.view().runtimeLine());

        reducer.reduce(start);
        reducer.reduce(new TurnEndEvent("ses_1", "turn_1", "FAILED", NOW));
        assertEquals("worked 0s", reducer.view().runtimeLine());

        reducer.reduce(start);
        reducer.configureRuntimeState(TestRuntimeStates.basic("ses_2"));
        assertEquals("", reducer.view().runtimeLine());
    }

    @Test
    void turnRuntimeLineShowsWorkingElapsedTime() {
        TuiEventReducer reducer = TuiEventReducer.withRuntimeState(TestRuntimeStates.basic("ses_1"));
        Instant startedAt = Instant.parse("2026-06-01T12:00:00Z");

        reducer.reduce(new TurnStartEvent("ses_1", "turn_1", startedAt, startedAt.plusSeconds(65)));

        assertEquals("working (1m5s)", reducer.view().runtimeLine());
        assertEquals(0, reducer.view().blocks().size());
    }

    @Test
    void turnRuntimeLineShowsWorkedDurationAfterTurnEnd() {
        TuiEventReducer reducer = TuiEventReducer.withRuntimeState(TestRuntimeStates.basic("ses_1"));
        Instant startedAt = Instant.parse("2026-06-01T12:00:00Z");
        Instant endedAt = Instant.parse("2026-06-01T13:02:03Z");

        reducer.reduce(new TurnStartEvent("ses_1", "turn_1", startedAt, startedAt));
        reducer.reduce(new TurnEndEvent("ses_1", "turn_1", "COMPLETED", startedAt, endedAt, 3_723_000L, endedAt));

        assertEquals("worked 1h2m3s", reducer.view().runtimeLine());
        assertEquals("EXECUTE", reducer.view().statusBar().mode());
        assertEquals(0, reducer.view().blocks().size());
    }

    @Test
    void turnRuntimeLineFormatsSecondsMinutesAndHours() {
        assertEquals("0s", TuiRenderState.formatTurnDuration(0L));
        assertEquals("59s", TuiRenderState.formatTurnDuration(59_000L));
        assertEquals("1m0s", TuiRenderState.formatTurnDuration(60_000L));
        assertEquals("59m59s", TuiRenderState.formatTurnDuration(3_599_000L));
        assertEquals("1h0m0s", TuiRenderState.formatTurnDuration(3_600_000L));
        assertEquals("2h3m4s", TuiRenderState.formatTurnDuration(7_384_000L));
    }

    @Test
    void sessionStartUpdatesStatusBarAndResponseMemoryEventsDoNotRender() {
        TuiEventReducer reducer = TuiEventReducer.withRuntimeState(TestRuntimeStates.basic("ses_1"));

        reducer.reduce(new SessionStartEvent("ses_2", NOW));

        assertEquals("ses_2", reducer.view().statusBar().sessionId());
        assertEquals("", reducer.view().runtimeLine());
        assertEquals(0, reducer.view().blocks().size());

        reducer.reduce(new PermissionResponseEvent("ses_2", "req_1", "allow_once", false, NOW));
        reducer.reduce(new MemoryWriteEvent("ses_2", Path.of("MEMORY.md"), NOW));

        assertEquals("ses_2", reducer.view().statusBar().sessionId());
        assertEquals("", reducer.view().runtimeLine());
        assertEquals(0, reducer.view().blocks().size());
        assertTrue(reducer.view().permissionPrompt().isEmpty());
    }

    @Test
    void runtimeStateProjectsStatusBarAndToolRunningState() {
        TuiEventReducer reducer = TuiEventReducer.withRuntimeState(TestRuntimeStates.basic("ses_1"));

        assertEquals("ses_1", reducer.view().statusBar().sessionId());
        assertEquals("gpt-5.4", reducer.view().statusBar().model());
        assertEquals("EXECUTE", reducer.view().statusBar().mode());
        assertEquals("DEFAULT_EXECUTE", reducer.view().statusBar().permissionMode());
        assertEquals("ON_REQUEST", reducer.view().statusBar().approvalMode());
        assertEquals(":workspace", reducer.view().statusBar().activePermissionProfileId());
        assertEquals("ly-pi", reducer.view().statusBar().cwd());
        assertEquals("leaf_1", reducer.view().statusBar().branchLeafId());
        assertEquals("1234/200000tok", reducer.view().statusBar().budget());
        assertFalse(reducer.view().statusBar().hasInterruptibleTool());

        reducer.reduce(new ToolStartEvent("ses_1", "toolu_1", "bash", NOW));

        assertEquals("EXECUTE", reducer.view().statusBar().mode());
        assertTrue(reducer.view().statusBar().hasInterruptibleTool());

        reducer.reduce(new ToolEndEvent("ses_1", "toolu_1", false, NOW.plusMillis(10)));

        assertEquals("EXECUTE", reducer.view().statusBar().mode());
        assertFalse(reducer.view().statusBar().hasInterruptibleTool());
    }

    @Test
    void runtimeStateProjectsCanonicalApprovalModeAndProfileId() {
        SessionRuntimeState runtimeState = new SessionRuntimeState(
            "ses_1",
            Path.of("/home/lyfmt/src/study/ly-pi"),
            "leaf_1",
            new ModelSelection("openai", "gpt-5.4", ThinkingLevel.HIGH),
            ThinkingLevel.HIGH,
            AgentMode.EXECUTE,
            new PermissionRuntimeState(
                new ApprovalPolicy(ApprovalMode.ON_FAILURE),
                new ActivePermissionProfile("project-dev", Optional.of(":workspace")),
                cn.lypi.contracts.security.PermissionProfiles.workspace(),
                PermissionRuntimeState.fromLegacy(PermissionMode.ACCEPT_EDITS).legacyBehavior(),
                PermissionMode.ACCEPT_EDITS
            ),
            TestRuntimeStates.basic("ses_1").budget(),
            List.of(),
            false,
            false,
            false,
            false
        );

        TuiEventReducer reducer = TuiEventReducer.withRuntimeState(runtimeState);

        assertEquals("ACCEPT_EDITS", reducer.view().statusBar().permissionMode());
        assertEquals("ON_FAILURE", reducer.view().statusBar().approvalMode());
        assertEquals("project-dev", reducer.view().statusBar().activePermissionProfileId());
    }

    @Test
    void runtimeStateProjectsResumedTranscriptBlocks() {
        SessionRuntimeState runtimeState = new SessionRuntimeState(
            "ses_old",
            Path.of("/home/lyfmt/src/study/ly-pi"),
            "leaf_old",
            new ModelSelection("openai", "gpt-5.4", ThinkingLevel.HIGH),
            ThinkingLevel.HIGH,
            AgentMode.EXECUTE,
            PermissionMode.DEFAULT_EXECUTE,
            TestRuntimeStates.basic("ses_old").budget(),
            List.of(
                new AgentMessage(
                    "msg_user",
                    MessageRole.USER,
                    MessageKind.TEXT,
                    List.of(new TextContentBlock("old prompt")),
                    NOW,
                    Optional.empty(),
                    Optional.empty()
                ),
                new AgentMessage(
                    "msg_assistant",
                    MessageRole.ASSISTANT,
                    MessageKind.TEXT,
                    List.of(new TextContentBlock("old answer")),
                    NOW.plusMillis(1),
                    Optional.empty(),
                    Optional.empty()
                )
            ),
            false,
            false,
            false,
            false
        );

        TuiEventReducer reducer = TuiEventReducer.withRuntimeState(runtimeState);

        assertEquals(2, reducer.view().blocks().size());
        TuiMessageBlock user = assertInstanceOf(TuiMessageBlock.class, reducer.view().blocks().get(0));
        TuiMessageBlock assistant = assertInstanceOf(TuiMessageBlock.class, reducer.view().blocks().get(1));
        assertEquals("user", user.role());
        assertEquals("old prompt", user.content());
        assertEquals("assistant", assistant.role());
        assertEquals("old answer", assistant.content());
    }

    @Test
    void runtimeTranscriptProjectsToolResultWithoutDuplicateToolMessage() {
        SessionRuntimeState base = TestRuntimeStates.basic("ses_old");
        SessionRuntimeState runtimeState = new SessionRuntimeState(
            base.sessionId(),
            Path.of("/home/lyfmt/src/study/ly-pi"),
            base.currentBranchLeafId(),
            base.model(),
            base.thinkingLevel(),
            base.agentMode(),
            base.permissionMode(),
            base.budget(),
            List.of(
                new AgentMessage(
                    "msg_user",
                    MessageRole.USER,
                    MessageKind.TEXT,
                    List.of(new TextContentBlock("read AGENTS")),
                    NOW,
                    Optional.empty(),
                    Optional.empty()
                ),
                new AgentMessage(
                    "msg_tool_call",
                    MessageRole.ASSISTANT,
                    MessageKind.TOOL_CALL,
                    List.of(new ToolCallContentBlock("call_1", "read", "", Map.of(
                        "input", Map.of("path", "AGENTS.md"),
                        "complete", true,
                        "inputSummary", "read {path=AGENTS.md}"
                    ))),
                    NOW.plusMillis(1),
                    Optional.empty(),
                    Optional.of("tool_calls")
                ),
                new AgentMessage(
                    "msg_tool_result",
                    MessageRole.TOOL_RESULT,
                    MessageKind.TOOL_RESULT,
                    List.of(new ToolResultContentBlock("call_1", "File: AGENTS.md\n1 | 用户名字叫末声", false)),
                    NOW.plusMillis(2),
                    Optional.empty(),
                    Optional.empty()
                )
            ),
            false,
            false,
            false,
            false
        );

        TuiEventReducer reducer = TuiEventReducer.fromRuntimeState(runtimeState);

        List<TuiBlock> blocks = reducer.view().blocks();
        long toolBlocks = blocks.stream().filter(TuiToolBlock.class::isInstance).count();
        long projectedToolMessages = blocks.stream()
            .filter(TuiMessageBlock.class::isInstance)
            .map(TuiMessageBlock.class::cast)
            .filter(block -> "tool".equals(block.role()))
            .count();
        assertEquals(1, toolBlocks);
        assertEquals(0, projectedToolMessages);
        TuiToolBlock tool = blocks.stream()
            .filter(TuiToolBlock.class::isInstance)
            .map(TuiToolBlock.class::cast)
            .findFirst()
            .orElseThrow();
        assertEquals("call_1", tool.toolUseId());
        assertEquals("read", tool.toolName());
    }

    @Test
    void sessionStateEventRefreshesStatusBarFromCurrentTreeProjection() {
        TuiEventReducer reducer = TuiEventReducer.withRuntimeState(TestRuntimeStates.basic("ses_1"));

        reducer.reduce(new SessionStateEvent(
            "ses_1",
            "leaf_2",
            new ModelSelection("anthropic", "claude-sonnet", ThinkingLevel.HIGH),
            ThinkingLevel.HIGH,
            AgentMode.PLAN,
            new PermissionRuntimeState(
                new ApprovalPolicy(ApprovalMode.NEVER),
                new ActivePermissionProfile(":danger-full-access"),
                cn.lypi.contracts.security.PermissionProfiles.dangerFullAccess(),
                PermissionRuntimeState.fromLegacy(PermissionMode.BYPASS).legacyBehavior(),
                PermissionMode.BYPASS
            ),
            NOW
        ));

        assertEquals("claude-sonnet", reducer.view().statusBar().model());
        assertEquals("PLAN", reducer.view().statusBar().mode());
        assertEquals("BYPASS", reducer.view().statusBar().permissionMode());
        assertEquals("NEVER", reducer.view().statusBar().approvalMode());
        assertEquals(":danger-full-access", reducer.view().statusBar().activePermissionProfileId());
        assertEquals("leaf_2", reducer.view().statusBar().branchLeafId());
    }

    @Test
    void repeatedToolStartDoesNotKeepStatusBarRunningAfterSingleEnd() {
        TuiEventReducer reducer = TuiEventReducer.withRuntimeState(TestRuntimeStates.basic("ses_1"));

        reducer.reduce(new ToolStartEvent("ses_1", "toolu_1", "bash", NOW));
        reducer.reduce(new ToolStartEvent("ses_1", "toolu_1", "bash", NOW.plusMillis(1)));
        reducer.reduce(new ToolEndEvent("ses_1", "toolu_1", false, NOW.plusMillis(2)));

        assertEquals("EXECUTE", reducer.view().statusBar().mode());
    }

    @Test
    void runtimeInterruptibleToolProjectsInterruptibleStateWithoutChangingVisibleMode() {
        TuiEventReducer reducer = TuiEventReducer.withRuntimeState(TestRuntimeStates.interruptible("ses_1"));

        assertEquals("EXECUTE", reducer.view().statusBar().mode());
        assertTrue(reducer.view().statusBar().hasInterruptibleTool());
    }

    @Test
    void runtimeInterruptibleToolEndRestoresAgentModeWithoutExistingToolBlock() {
        TuiEventReducer reducer = TuiEventReducer.withRuntimeState(TestRuntimeStates.interruptible("ses_1"));

        reducer.reduce(new ToolEndEvent("ses_1", "toolu_1", false, NOW.plusMillis(1)));

        assertEquals("EXECUTE", reducer.view().statusBar().mode());
        assertFalse(reducer.view().statusBar().hasInterruptibleTool());
    }

    @Test
    void configureRuntimeStateClearsPreviousRunningToolState() {
        TuiEventReducer reducer = TuiEventReducer.withRuntimeState(TestRuntimeStates.basic("ses_1"));
        reducer.reduce(new ToolStartEvent("ses_1", "toolu_1", "bash", NOW));

        reducer.configureRuntimeState(TestRuntimeStates.basic("ses_2"));

        assertEquals("ses_2", reducer.view().statusBar().sessionId());
        assertEquals("EXECUTE", reducer.view().statusBar().mode());
        assertFalse(reducer.view().statusBar().hasInterruptibleTool());
    }

    @Test
    void reducesMessageThinkingAndToolEventsToLightweightBlocks() {
        TuiEventReducer reducer = new TuiEventReducer();

        reducer.reduce(new MessageStartEvent("ses_1", "msg_1", MessageRole.ASSISTANT, MessageKind.TEXT, Map.of(), NOW));
        reducer.reduce(new MessageDeltaEvent(
            "ses_1",
            "msg_1",
            MessageRole.ASSISTANT,
            MessageKind.TEXT,
            "block-text",
            ContentBlockKind.TEXT,
            "hello ",
            false,
            Map.of(),
            NOW
        ));
        reducer.reduce(new MessageDeltaEvent(
            "ses_1",
            "msg_1",
            MessageRole.ASSISTANT,
            MessageKind.TEXT,
            "block-text",
            ContentBlockKind.TEXT,
            "world",
            true,
            Map.of(),
            NOW
        ));
        reducer.reduce(new MessageDeltaEvent(
            "ses_1",
            "msg_1",
            MessageRole.ASSISTANT,
            MessageKind.THINKING,
            "block-thinking",
            ContentBlockKind.THINKING,
            "thinking",
            false,
            Map.of(),
            NOW
        ));
        reducer.reduce(new ToolStartEvent(
            "ses_1",
            "toolu_1",
            "msg_1",
            "turn_1",
            "bash",
            "Bash",
            "echo hello",
            Map.of("command", "echo hello"),
            NOW,
            NOW
        ));
        TuiViewModel afterStart = reducer.view();

        reducer.reduce(new ToolProgressEvent("ses_1", "toolu_1", ToolProgress.output("stdout", "hello\n"), NOW));
        reducer.reduce(new ToolEndEvent(
            "ses_1",
            "toolu_1",
            ToolExecutionStatus.SUCCEEDED,
            0,
            new ToolResultSummary("bash succeeded", "hello", false, 0, false, 6L, Map.of()),
            new ToolOutputRef(
                "ref_1",
                "ses_1",
                "toolu_1",
                "text/plain",
                "memory",
                "mem://toolu_1",
                "sha256:abc",
                6L,
                Map.of("preview", "hello")
            ),
            NOW,
            NOW.plusMillis(10),
            10L,
            Map.of("resultMetadata", "must-not-enter-tool-block"),
            NOW.plusMillis(10)
        ));

        List<TuiBlock> blocks = reducer.view().blocks();

        TuiMessageBlock message = assertInstanceOf(TuiMessageBlock.class, blocks.get(0));
        assertEquals(TuiBlockKind.MESSAGE, message.kind());
        assertEquals("hello world", message.content());
        assertFalse(message.streaming());

        TuiThinkingBlock thinking = assertInstanceOf(TuiThinkingBlock.class, blocks.get(1));
        assertEquals(TuiBlockKind.THINKING, thinking.kind());
        assertEquals("thinking", thinking.content());
        assertTrue(thinking.streaming());
        assertFalse(thinking.collapsed());

        TuiToolBlock toolAfterStart = assertInstanceOf(TuiToolBlock.class, afterStart.blocks().get(2));
        assertEquals("toolu_1", toolAfterStart.toolUseId());
        assertEquals("bash", toolAfterStart.toolName());
        assertEquals("echo hello", toolAfterStart.label());
        assertEquals(TuiToolState.RUNNING, toolAfterStart.state());
        assertTrue(toolAfterStart.active());

        TuiToolBlock tool = assertInstanceOf(TuiToolBlock.class, blocks.get(2));
        assertEquals(TuiBlockKind.TOOL, tool.kind());
        assertEquals(TuiToolState.DONE, tool.state());
        assertFalse(tool.active());

        assertEquals(3, blocks.size(), "tool progress must not append transcript lines");
        assertEquals("echo hello", tool.label(), "tool end must not replace label with result summary");
    }

    @Test
    void permissionRequestAndDecisionControlOverlayWithoutKeepingToolDetails() {
        TuiEventReducer reducer = new TuiEventReducer();

        reducer.reduce(new PermissionRequestEvent("ses_1", "toolu_1", "Need approval", NOW));

        assertTrue(reducer.view().permissionPrompt().isPresent());
        var prompt = reducer.view().permissionPrompt().orElseThrow();
        assertEquals("toolu_1", prompt.requestId());
        assertEquals("toolu_1", prompt.toolUseId());
        assertEquals("allow_once", prompt.defaultOptionId());
        assertEquals(List.of("allow_once", "deny", "cancel"),
            prompt.options().stream().map(option -> option.optionId()).toList());

        reducer.reduce(new PermissionDecisionEvent(
            "ses_1",
            "toolu_1",
            new PermissionDecision(
                PermissionBehavior.DENY,
                PermissionDecisionReason.TOOL_SPECIFIC,
                "Denied",
                Optional.empty(),
                Map.of()
            ),
            NOW
        ));

        TuiViewModel afterDecision = reducer.view();
        assertTrue(afterDecision.permissionPrompt().isEmpty());
        assertEquals(0, afterDecision.blocks().size(), "permission decision only controls the overlay");
    }

    @Test
    void requestPermissionsPromptProjectsAvailableDecisionsAndRequestedDelta() {
        TuiEventReducer reducer = new TuiEventReducer();

        reducer.reduce(new PermissionRequestEvent(
            "ses_1",
            "req_1",
            "toolu_1",
            "request_permissions",
            "Request permissions",
            "{\"network\":\"enabled\"}",
            "Need network for dependency download",
            new PermissionDecision(
                PermissionBehavior.ASK,
                PermissionDecisionReason.TOOL_SPECIFIC,
                "Need network for dependency download",
                Optional.empty(),
                Map.of()
            ),
            ApprovalKind.REQUEST_PERMISSIONS,
            List.of(ReviewDecision.APPROVED, ReviewDecision.DENIED, ReviewDecision.ABORT),
            Optional.of(new AdditionalPermissionProfile(
                Optional.of(FileSystemPermissionPolicy.unrestricted()),
                Optional.of(NetworkPermissionPolicy.enabled())
            )),
            false,
            List.of(
                option("approved", PermissionOptionKind.ALLOW_ONCE, "Approve", ReviewDecision.APPROVED),
                option("denied", PermissionOptionKind.DENY, "Deny", ReviewDecision.DENIED),
                option("abort", PermissionOptionKind.CANCEL, "Abort", ReviewDecision.ABORT)
            ),
            "approved",
            "abort",
            Map.of(),
            NOW
        ));

        var prompt = reducer.view().permissionPrompt().orElseThrow();
        assertEquals("req_1", prompt.requestId());
        assertTrue(prompt.reason().contains("REQUEST_PERMISSIONS"));
        assertTrue(prompt.reason().contains("Need network for dependency download"));
        assertTrue(prompt.rule().contains("filesystem=UNRESTRICTED"));
        assertTrue(prompt.rule().contains("network=ENABLED"));
        assertEquals(List.of("approved", "denied", "abort"),
            prompt.options().stream().map(PermissionOption::optionId).toList());
    }

    @Test
    void interruptClearsPermissionPromptOverlay() {
        TuiEventReducer reducer = new TuiEventReducer();

        reducer.reduce(new PermissionRequestEvent("ses_1", "toolu_1", "Need approval", NOW));
        assertTrue(reducer.view().permissionPrompt().isPresent());

        reducer.reduce(new InterruptEvent("ses_1", "esc", NOW));

        assertTrue(reducer.view().permissionPrompt().isEmpty());
        assertEquals("interrupted esc", reducer.view().runtimeLine());
    }

    private PermissionOption option(
        String id,
        PermissionOptionKind kind,
        String label,
        ReviewDecision reviewDecision
    ) {
        return new PermissionOption(id, kind, label, "", Optional.empty(), Map.of("reviewDecision", reviewDecision.name()));
    }

    @Test
    void errorEventAppendsErrorBlock() {
        TuiEventReducer reducer = new TuiEventReducer();

        reducer.reduce(new ErrorEvent("ses_1", "err_1", "boom", NOW));

        TuiErrorBlock error = assertInstanceOf(TuiErrorBlock.class, reducer.view().blocks().getFirst());
        assertEquals(TuiBlockKind.ERROR, error.kind());
        assertEquals("boom", error.message());
    }

    @Test
    void messageErrorDeltaCreatesErrorBlock() {
        TuiEventReducer reducer = new TuiEventReducer();

        reducer.reduce(new MessageDeltaEvent(
            "ses_1",
            "msg_error",
            MessageRole.ASSISTANT,
            MessageKind.ERROR,
            "msg_error:error:0",
            ContentBlockKind.ERROR,
            "provider stream failed",
            true,
            Map.of("errorId", "provider-error"),
            NOW
        ));

        TuiErrorBlock error = assertInstanceOf(TuiErrorBlock.class, reducer.view().blocks().getFirst());
        assertEquals(TuiBlockKind.ERROR, error.kind());
        assertEquals("msg_error:error:0", error.blockId());
        assertEquals("provider stream failed", error.message());
    }

    @Test
    void messageEndSnapshotReusesStreamingTextBlockWhenThinkingPrecedesText() {
        TuiEventReducer reducer = new TuiEventReducer();

        reducer.reduce(new MessageStartEvent(
            "ses_1",
            "msg_assistant",
            MessageRole.ASSISTANT,
            MessageKind.TEXT,
            Map.of("streaming", true, "kindProvisional", true),
            NOW
        ));
        reducer.reduce(new MessageDeltaEvent(
            "ses_1",
            "msg_assistant",
            MessageRole.ASSISTANT,
            MessageKind.TEXT,
            "msg_assistant:text:0",
            ContentBlockKind.TEXT,
            "answer",
            false,
            Map.of(),
            NOW
        ));
        reducer.reduce(new MessageEndEvent(
            "ses_1",
            "msg_assistant",
            MessageRole.ASSISTANT,
            MessageKind.ERROR,
            List.of(
                new MessageBlockSnapshot("msg_assistant:thinking:0", ContentBlockKind.THINKING, "thinking", Map.of()),
                new MessageBlockSnapshot("msg_assistant:text:0", ContentBlockKind.TEXT, "answer", Map.of()),
                new MessageBlockSnapshot("msg_assistant:error:0", ContentBlockKind.ERROR, "Provider HTTP request failed.", Map.of())
            ),
            Optional.empty(),
            Optional.of("error"),
            Map.of("streaming", true),
            NOW
        ));

        List<TuiMessageBlock> messages = reducer.view().blocks().stream()
            .filter(TuiMessageBlock.class::isInstance)
            .map(TuiMessageBlock.class::cast)
            .toList();
        assertEquals(1, messages.size());
        assertEquals("msg_assistant:text:0", messages.getFirst().blockId());
        assertEquals("answer", messages.getFirst().content());
    }

    @Test
    void toolCallDeltaCreatesPendingToolBlockThenToolStartUpdatesItInPlace() {
        TuiEventReducer reducer = new TuiEventReducer();

        reducer.reduce(new MessageDeltaEvent(
            "ses_1",
            "msg_tool_call",
            MessageRole.ASSISTANT,
            MessageKind.TOOL_CALL,
            "msg_tool_call:tool_call:toolu_1",
            ContentBlockKind.TOOL_CALL,
            "",
            false,
            Map.of(
                "toolUseId", "toolu_1",
                "toolName", "read",
                "partialInput", Map.of("path", "pom.xml"),
                "complete", false,
                "inputSummary", "read {path=pom.xml}"
            ),
            NOW
        ));

        List<TuiBlock> pendingBlocks = reducer.view().blocks();
        assertEquals(1, pendingBlocks.size());
        TuiToolBlock pending = assertInstanceOf(TuiToolBlock.class, pendingBlocks.getFirst());
        assertEquals("toolu_1", pending.toolUseId());
        assertEquals("read", pending.toolName());
        assertEquals(TuiToolState.PENDING, pending.state());
        assertEquals("read {path=pom.xml}", pending.label());
        assertTrue(pending.active());

        reducer.reduce(new ToolStartEvent(
            "ses_1",
            "toolu_1",
            "msg_tool_call",
            "turn_1",
            "read",
            "Read",
            "pom.xml",
            Map.of("path", "pom.xml"),
            NOW,
            NOW
        ));

        List<TuiBlock> runningBlocks = reducer.view().blocks();
        assertEquals(1, runningBlocks.size());
        TuiToolBlock running = assertInstanceOf(TuiToolBlock.class, runningBlocks.getFirst());
        assertEquals(TuiToolState.RUNNING, running.state());
        assertEquals("pom.xml", running.label());
        assertTrue(running.active());
    }

    @Test
    void messageEndDeactivatesPendingToolBlockWhenToolExecutionNeverStarts() {
        TuiEventReducer reducer = new TuiEventReducer();

        reducer.reduce(new MessageDeltaEvent(
            "ses_1",
            "msg_tool_call",
            MessageRole.ASSISTANT,
            MessageKind.TOOL_CALL,
            "msg_tool_call:tool_call:toolu_1",
            ContentBlockKind.TOOL_CALL,
            "",
            false,
            Map.of(
                "toolUseId", "toolu_1",
                "toolName", "read",
                "inputSummary", "read {path=pom.xml}"
            ),
            NOW
        ));
        reducer.reduce(new MessageEndEvent(
            "ses_1",
            "msg_tool_call",
            MessageRole.ASSISTANT,
            MessageKind.ERROR,
            List.of(),
            Optional.empty(),
            Optional.of("error"),
            Map.of(),
            NOW
        ));

        TuiToolBlock block = assertInstanceOf(TuiToolBlock.class, reducer.view().blocks().getFirst());
        assertEquals(TuiToolState.PENDING, block.state());
        assertFalse(block.active());
    }

    @Test
    void messageEndToolSnapshotUpdatesStreamingToolBlockWithoutUnknownDuplicate() {
        TuiEventReducer reducer = new TuiEventReducer();

        reducer.reduce(new MessageDeltaEvent(
            "ses_1",
            "msg_tool_call",
            MessageRole.ASSISTANT,
            MessageKind.TOOL_CALL,
            "msg_tool_call:tool_call:call_1",
            ContentBlockKind.TOOL_CALL,
            "",
            false,
            Map.of(
                "toolUseId", "call_1",
                "toolName", "glob",
                "inputSummary", "glob {pattern=*.java}"
            ),
            NOW
        ));
        reducer.reduce(new MessageEndEvent(
            "ses_1",
            "msg_tool_call",
            MessageRole.ASSISTANT,
            MessageKind.TOOL_CALL,
            List.of(new MessageBlockSnapshot("msg_tool_call:tool_call:0", ContentBlockKind.TOOL_CALL, "", Map.of(
                "toolUseId", "call_1",
                "toolName", "glob",
                "inputSummary", "glob {pattern=*.java}"
            ))),
            Optional.empty(),
            Optional.of("tool_calls"),
            Map.of(),
            NOW
        ));

        List<TuiBlock> blocks = reducer.view().blocks();
        assertEquals(1, blocks.size());
        TuiToolBlock tool = assertInstanceOf(TuiToolBlock.class, blocks.getFirst());
        assertEquals("call_1", tool.toolUseId());
        assertEquals("glob", tool.toolName());
        assertEquals("glob {pattern=*.java}", tool.label());
        assertFalse(tool.active());
    }

    @Test
    void messageEndOnlyDeactivatesPendingToolBlocksFromSameMessage() {
        TuiEventReducer reducer = new TuiEventReducer();

        reducer.reduce(toolCallDelta("msg_tool_call_1", "toolu_1", "read"));
        reducer.reduce(toolCallDelta("msg_tool_call_2", "toolu_2", "grep"));
        reducer.reduce(new MessageEndEvent(
            "ses_1",
            "msg_tool_call_1",
            MessageRole.ASSISTANT,
            MessageKind.TOOL_CALL,
            List.of(),
            Optional.empty(),
            Optional.of("tool_calls"),
            Map.of(),
            NOW
        ));

        TuiToolBlock first = assertInstanceOf(TuiToolBlock.class, reducer.view().blocks().get(0));
        TuiToolBlock second = assertInstanceOf(TuiToolBlock.class, reducer.view().blocks().get(1));
        assertEquals("msg_tool_call_1", first.messageId());
        assertFalse(first.active());
        assertEquals("msg_tool_call_2", second.messageId());
        assertTrue(second.active());
    }

    @Test
    void replayInitializationCreatesEmptyFirstScreenWhenOnlySessionPointerExists() {
        TuiEventReducer reducer = TuiEventReducer.fromSessionView(new SessionView("ses_1", "leaf_1"));

        assertTrue(reducer.view().blocks().isEmpty());
        assertTrue(reducer.view().files().isEmpty());
        assertTrue(reducer.view().permissionPrompt().isEmpty());
    }

    @Test
    void runtimeStateInitializesStatusBar() {
        TuiEventReducer reducer = TuiEventReducer.fromRuntimeState(TestRuntimeStates.basic("ses_1"));

        StatusBarState status = reducer.view().statusBar();
        assertEquals("ses_1", status.sessionId());
        assertEquals("gpt-5.4", status.model());
        assertEquals("EXECUTE", status.mode());
        assertEquals("DEFAULT_EXECUTE", status.permissionMode());
        assertEquals("ly-pi", status.cwd());
        assertEquals("leaf_1", status.branchLeafId());
        assertEquals("1234/200000tok", status.budget());
        assertTrue(reducer.view().blocks().isEmpty(), "new session transcript stays empty");
    }

    private static MessageDeltaEvent toolCallDelta(String messageId, String toolUseId, String toolName) {
        return new MessageDeltaEvent(
            "ses_1",
            messageId,
            MessageRole.ASSISTANT,
            MessageKind.TOOL_CALL,
            messageId + ":tool_call:" + toolUseId,
            ContentBlockKind.TOOL_CALL,
            "",
            false,
            Map.of(
                "toolUseId", toolUseId,
                "toolName", toolName,
                "inputSummary", toolName
            ),
            NOW
        );
    }

    private static void assertToolEndState(ToolExecutionStatus status, TuiToolState expectedState) {
        TuiEventReducer reducer = new TuiEventReducer();
        reducer.reduce(new ToolStartEvent(
            "ses_1",
            "toolu_1",
            "msg_1",
            "turn_1",
            "bash",
            "Bash",
            "mvn test",
            Map.of(),
            NOW,
            NOW
        ));
        reducer.reduce(new ToolEndEvent(
            "ses_1",
            "toolu_1",
            status,
            status == ToolExecutionStatus.CANCELLED ? null : 1,
            new ToolResultSummary("bash " + status.name().toLowerCase(), "summary", status != ToolExecutionStatus.SUCCEEDED, 1, false, 0L, Map.of()),
            null,
            NOW,
            NOW.plusMillis(20),
            20L,
            Map.of(),
            NOW.plusMillis(20)
        ));

        TuiToolBlock tool = assertInstanceOf(TuiToolBlock.class, reducer.view().blocks().getFirst());
        assertEquals(expectedState, tool.state());
        assertFalse(tool.active());
    }
}
