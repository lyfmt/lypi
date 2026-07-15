package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.common.ToolProgress;
import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContentReplacementRecord;
import cn.lypi.contracts.context.ContentBlockKind;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.ToolCallContentBlock;
import cn.lypi.contracts.context.ToolResultContentBlock;
import cn.lypi.contracts.event.MessageDeltaEvent;
import cn.lypi.contracts.event.MessageEndEvent;
import cn.lypi.contracts.event.MessageStartEvent;
import cn.lypi.contracts.event.PermissionDecisionEvent;
import cn.lypi.contracts.event.PermissionRequestEvent;
import cn.lypi.contracts.event.ToolEndEvent;
import cn.lypi.contracts.event.ToolProgressEvent;
import cn.lypi.contracts.event.ToolStartEvent;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionDecisionReason;
import cn.lypi.contracts.security.AdditionalPermissionProfile;
import cn.lypi.contracts.security.ApprovalKind;
import cn.lypi.contracts.security.FileSystemAccessMode;
import cn.lypi.contracts.security.FileSystemPermissionEntry;
import cn.lypi.contracts.security.FileSystemPermissionPolicy;
import cn.lypi.contracts.security.FileSystemPath;
import cn.lypi.contracts.security.NetworkPermissionPolicy;
import cn.lypi.contracts.security.PermissionOption;
import cn.lypi.contracts.security.PermissionOptionKind;
import cn.lypi.contracts.security.PermissionOptionPolicy;
import cn.lypi.contracts.security.PermissionRule;
import cn.lypi.contracts.security.PermissionRuleSource;
import cn.lypi.contracts.security.PermissionRuleValue;
import cn.lypi.contracts.security.PermissionUpdate;
import cn.lypi.contracts.security.ReviewDecision;
import cn.lypi.contracts.session.SessionView;
import cn.lypi.contracts.tool.ToolExecutionStatus;
import cn.lypi.contracts.tool.ToolOutputRef;
import cn.lypi.contracts.tool.ToolResultSummary;
import cn.lypi.contracts.tui.PermissionPromptView;
import cn.lypi.contracts.tui.SessionRuntimeState;
import cn.lypi.contracts.tui.StatusBarState;
import cn.lypi.contracts.tui.TuiBlock;
import cn.lypi.contracts.tui.TuiMessageBlock;
import cn.lypi.contracts.tui.TuiThinkingBlock;
import cn.lypi.contracts.tui.TuiToolBlock;
import cn.lypi.contracts.tui.TuiToolState;
import cn.lypi.contracts.tui.TuiViewModel;
import java.time.Instant;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class TuiContractEndToEndTest {
    private static final Instant NOW = Instant.parse("2026-06-07T10:00:00Z");

    @Test
    void assistantThinkingToolLifecycleAndWindowedOutputRenderFromSemanticEvents() {
        TuiEventReducer reducer = new TuiEventReducer();

        reducer.reduce(new MessageStartEvent("ses_1", "msg_1", MessageRole.ASSISTANT, MessageKind.TEXT, Map.of(), NOW));
        reducer.reduce(delta("msg_1", "text_1", ContentBlockKind.TEXT, "hello ", false));
        reducer.reduce(delta("msg_1", "thinking_1", ContentBlockKind.THINKING, "private chain", false));
        reducer.reduce(delta("msg_1", "text_1", ContentBlockKind.TEXT, "world", true));
        reducer.reduce(new MessageEndEvent(
            "ses_1",
            "msg_1",
            MessageRole.ASSISTANT,
            MessageKind.TEXT,
            List.of(),
            Optional.empty(),
            Optional.of("stop"),
            Map.of("turnId", "turn_1"),
            NOW
        ));
        ToolStartEvent start = new ToolStartEvent(
            "ses_1",
            "toolu_bash",
            "msg_1",
            "turn_1",
            "bash",
            "Run shell",
            "printf output",
            Map.of("command", "printf output"),
            NOW,
            NOW
        );
        ToolProgressEvent stdout = new ToolProgressEvent("ses_1", "toolu_bash", ToolProgress.output("stdout", "line 1\n"), NOW);
        ToolProgressEvent stderr = new ToolProgressEvent("ses_1", "toolu_bash", ToolProgress.output("stderr", "warn\n"), NOW);
        ToolOutputRef resultRef = new ToolOutputRef(
            "out_ref_1",
            "ses_1",
            "toolu_bash",
            "text/plain",
            "tool-result-store",
            "tool-output://ses_1/toolu_bash",
            "sha256:abc",
            4096L,
            Map.of("stdoutBytes", 2048, "stderrBytes", 2048)
        );
        ToolEndEvent end = new ToolEndEvent(
            "ses_1",
            "toolu_bash",
            ToolExecutionStatus.SUCCEEDED,
            0,
            new ToolResultSummary("completed", "short preview", false, 0, false, 4096L, Map.of("windowed", true)),
            resultRef,
            NOW,
            NOW.plusMillis(50),
            50L,
            Map.of("turnId", "turn_1"),
            NOW.plusMillis(50)
        );
        reducer.reduce(start);
        reducer.reduce(stdout);
        reducer.reduce(stderr);
        reducer.reduce(end);

        List<TuiBlock> blocks = reducer.view().blocks();
        TuiMessageBlock message = assertInstanceOf(TuiMessageBlock.class, blocks.get(0));
        TuiThinkingBlock thinking = assertInstanceOf(TuiThinkingBlock.class, blocks.get(1));
        TuiToolBlock tool = assertInstanceOf(TuiToolBlock.class, blocks.get(2));

        assertEquals("hello world", message.content());
        assertFalse(message.streaming());
        assertEquals("private chain", thinking.content());
        assertFalse(thinking.streaming());
        assertFalse(thinking.collapsed());
        assertEquals("toolu_bash", tool.toolUseId());
        assertEquals("bash", tool.toolName());
        assertEquals("printf output", tool.label());
        assertEquals(TuiToolState.DONE, tool.state());
        assertFalse(tool.active());
        assertEquals(3, blocks.size(), "stdout/stderr progress stays structured and does not become guessed transcript text");
        assertEquals(start.toolUseId(), stdout.toolUseId());
        assertEquals(start.toolUseId(), stderr.toolUseId());
        assertEquals(start.toolUseId(), end.toolUseId());
        assertEquals(start.sessionId(), resultRef.sessionId());
        assertEquals(start.toolUseId(), resultRef.toolUseId());
        assertEquals("turn_1", end.metadata().get("turnId"));
        assertEquals("tool-output://ses_1/toolu_bash", end.resultRef().location());

        ContentReplacementRecord replacement = new ContentReplacementRecord(
            "msg_result",
            end.toolUseId(),
            start.toolName(),
            Path.of(".lypi/tool-output/toolu_bash.txt"),
            end.resultSummary().summary(),
            6000,
            200
        );
        assertEquals(end.toolUseId(), replacement.toolUseId());
        assertEquals("bash", replacement.toolName());
        assertEquals(Path.of(".lypi/tool-output/toolu_bash.txt"), replacement.persistedPath());
    }

    @ParameterizedTest
    @MethodSource("terminalToolStates")
    void liveAndResumedToolUseHaveEquivalentFinalProjection(
        ToolExecutionStatus status,
        boolean error,
        TuiToolState expectedState
    ) {
        String resultText = "command output\nsecond line";
        String resultSummary = "command output second line (+1 lines)";
        TuiEventReducer liveReducer = new TuiEventReducer();
        liveReducer.reduce(new ToolStartEvent(
            "ses_1",
            "toolu_bash",
            "msg_tool",
            "turn_1",
            "bash",
            "Run shell",
            "printf output",
            Map.of("command", "printf output"),
            NOW,
            NOW
        ));
        liveReducer.reduce(new ToolProgressEvent(
            "ses_1",
            "toolu_bash",
            ToolProgress.output("stdout", "transient progress\n"),
            NOW.plusMillis(1)
        ));
        liveReducer.reduce(new ToolEndEvent(
            "ses_1",
            "toolu_bash",
            status,
            status == ToolExecutionStatus.SUCCEEDED ? 0 : 1,
            new ToolResultSummary(
                "bash " + status.name().toLowerCase(),
                resultSummary,
                error,
                status == ToolExecutionStatus.SUCCEEDED ? 0 : 1,
                false,
                resultText.length(),
                Map.of()
            ),
            null,
            NOW,
            NOW.plusMillis(2),
            2L,
            Map.of(),
            NOW.plusMillis(2)
        ));

        SessionRuntimeState base = TestRuntimeStates.basic("ses_1");
        SessionRuntimeState resumedState = new SessionRuntimeState(
            base.sessionId(),
            base.cwd(),
            base.currentBranchLeafId(),
            base.model(),
            base.thinkingLevel(),
            base.agentMode(),
            base.permissionRuntimeState(),
            base.budget(),
            List.of(
                new AgentMessage(
                    "msg_tool",
                    MessageRole.ASSISTANT,
                    MessageKind.TOOL_CALL,
                    List.of(new ToolCallContentBlock(
                        "toolu_bash",
                        "bash",
                        "",
                        Map.of("inputSummary", "printf output")
                    )),
                    NOW,
                    Optional.empty(),
                    Optional.of("tool_calls")
                ),
                new AgentMessage(
                    "msg_result",
                    MessageRole.TOOL_RESULT,
                    MessageKind.TOOL_RESULT,
                    List.of(new ToolResultContentBlock(
                        "toolu_bash",
                        resultText,
                        error,
                        Map.of("status", status.name())
                    )),
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
        TuiViewModel liveView = liveReducer.view();
        TuiViewModel resumedView = TuiEventReducer.fromRuntimeState(resumedState).view();
        TuiToolBlock liveTool = assertInstanceOf(TuiToolBlock.class, liveView.blocks().getFirst());
        TuiToolBlock resumedTool = assertInstanceOf(TuiToolBlock.class, resumedView.blocks().getFirst());

        assertEquals(liveTool.toolUseId(), resumedTool.toolUseId());
        assertEquals(liveTool.toolName(), resumedTool.toolName());
        assertEquals(expectedState, liveTool.state());
        assertEquals(liveTool.state(), resumedTool.state());
        assertEquals(liveTool.label(), resumedTool.label());
        assertEquals(liveTool.active(), resumedTool.active());

        List<String> liveLines = renderedTranscript(liveView);
        List<String> resumedLines = renderedTranscript(resumedView);
        assertEquals(liveLines.getFirst(), resumedLines.getFirst());
        assertEquals("  " + resultSummary, liveLines.getLast());
        assertEquals(liveLines.getLast(), resumedLines.getLast());
    }

    private static Stream<Arguments> terminalToolStates() {
        return Stream.of(
            Arguments.of(ToolExecutionStatus.SUCCEEDED, false, TuiToolState.DONE),
            Arguments.of(ToolExecutionStatus.FAILED, true, TuiToolState.FAILED)
        );
    }

    @Test
    void permissionPromptUsesEventOptionsAndDecisionOnlyClearsOverlay() {
        TuiEventReducer reducer = new TuiEventReducer();
        PermissionUpdate update = new PermissionUpdate(
            PermissionRuleSource.SESSION,
            new PermissionRule(
                PermissionRuleSource.SESSION,
                PermissionBehavior.ALLOW,
                new PermissionRuleValue("bash", "npm test"),
                "remember npm test"
            )
        );
        List<PermissionOption> options = List.of(
            new PermissionOption("allow_once", PermissionOptionKind.ALLOW_ONCE, "允许一次", "", Optional.empty(), Map.of()),
            new PermissionOption("remember", PermissionOptionKind.ALLOW_AND_REMEMBER, "允许并记住", "", Optional.of(update), Map.of()),
            new PermissionOption("deny", PermissionOptionKind.DENY, "拒绝", "", Optional.empty(), Map.of()),
            new PermissionOption("escape_cancel", PermissionOptionKind.CANCEL, "取消", "", Optional.empty(), Map.of())
        );

        reducer.reduce(new PermissionRequestEvent(
            "ses_1",
            "perm_1",
            "toolu_bash",
            "bash",
            "Run npm test",
            "npm test",
            "需要允许执行 npm test",
            decision(PermissionBehavior.ASK, "需要审批", Optional.of(update)),
            options,
            "remember",
            "escape_cancel",
            Map.of("turnId", "turn_1"),
            NOW
        ));

        PermissionPromptView prompt = reducer.view().permissionPrompt().orElseThrow();
        assertEquals("perm_1", prompt.requestId());
        assertEquals("toolu_bash", prompt.toolUseId());
        assertEquals("需要允许执行 npm test", prompt.reason());
        assertEquals("bash:npm test", prompt.rule());
        assertEquals("remember", prompt.defaultOptionId());
        assertEquals("escape_cancel", prompt.cancelOptionId());
        assertEquals(List.of("allow_once", "remember", "deny", "escape_cancel"),
            prompt.options().stream().map(PermissionOption::optionId).toList());
        assertEquals("remember", prompt.selectedOptionId());

        PermissionDecisionEvent decisionEvent = new PermissionDecisionEvent(
            "ses_1",
            "perm_1",
            "toolu_bash",
            "bash",
            "remember",
            decision(PermissionBehavior.ALLOW, "remembered", Optional.of(update)),
            Optional.of(update),
            Map.of("turnId", "turn_1"),
            NOW.plusMillis(1)
        );
        reducer.reduce(decisionEvent);

        TuiViewModel afterDecision = reducer.view();
        assertTrue(afterDecision.permissionPrompt().isEmpty());
        assertTrue(afterDecision.blocks().isEmpty(), "permission decisions are runtime events, not transcript entries");
        assertEquals("perm_1", decisionEvent.requestId());
        assertEquals("toolu_bash", decisionEvent.toolUseId());
        assertEquals("remember", decisionEvent.selectedOptionId());
        assertEquals(update, decisionEvent.appliedUpdate().orElseThrow());
    }

    @Test
    void requestPermissionsPromptProjectsCodexStyleAdditionalPermissions() {
        TuiEventReducer reducer = new TuiEventReducer();
        PermissionOptionPolicy.Options options = PermissionOptionPolicy.forAdditionalPermissionsApproval();
        AdditionalPermissionProfile additionalPermissions = new AdditionalPermissionProfile(
            Optional.of(FileSystemPermissionPolicy.restricted(List.of(new FileSystemPermissionEntry(
                FileSystemPath.exactPath("/workspace/reports"),
                FileSystemAccessMode.WRITE
            )))),
            Optional.of(NetworkPermissionPolicy.enabled())
        );

        reducer.reduce(new PermissionRequestEvent(
            "ses_1",
            "perm_2",
            "toolu_request_permissions",
            "request_permissions",
            "Request additional permissions",
            "{\"sandbox_permissions\":\"with_escalated_permissions\"}",
            "需要写入 reports 目录并访问网络",
            decision(PermissionBehavior.ASK, "review additional permissions", Optional.empty()),
            ApprovalKind.REQUEST_PERMISSIONS,
            List.of(ReviewDecision.APPROVED, ReviewDecision.ABORT),
            Optional.of(additionalPermissions),
            true,
            options.options(),
            options.defaultOptionId(),
            options.cancelOptionId(),
            Map.of("turnId", "turn_2"),
            NOW
        ));

        PermissionPromptView prompt = reducer.view().permissionPrompt().orElseThrow();
        assertEquals("perm_2", prompt.requestId());
        assertEquals("toolu_request_permissions", prompt.toolUseId());
        assertTrue(prompt.reason().contains("REQUEST_PERMISSIONS"));
        assertTrue(prompt.reason().contains("需要写入 reports 目录并访问网络"));
        assertTrue(prompt.reason().contains("APPROVED"));
        assertTrue(prompt.reason().contains("ABORT"));
        assertTrue(prompt.rule().contains("filesystem=RESTRICTED"));
        assertTrue(prompt.rule().contains("/workspace/reports"));
        assertTrue(prompt.rule().contains("network=ENABLED"));
        assertEquals(List.of("approved", "abort"), prompt.options().stream().map(PermissionOption::optionId).toList());
        assertEquals("approved", prompt.defaultOptionId());
        assertEquals("abort", prompt.cancelOptionId());
    }

    @Test
    void restoredSessionPointerDoesNotSmuggleRecentFilesPermissionsOrToolsIntoView() {
        TuiEventReducer reducer = TuiEventReducer.fromSessionView(new SessionView("ses_1", "leaf_right"));

        TuiViewModel view = reducer.view();

        assertTrue(view.blocks().isEmpty());
        assertTrue(view.files().isEmpty());
        assertTrue(view.permissionPrompt().isEmpty());
        assertTrue(view.diffView().isEmpty());
    }

    @Test
    void toolLifecycleDetailsRespectCollapsedAndExpandedDisplayBudgets() {
        TuiEventReducer reducer = new TuiEventReducer();
        reducer.reduce(new ToolStartEvent(
            "ses_1",
            "toolu_bash",
            "msg_1",
            "turn_1",
            "bash",
            "Run shell",
            "bash mvn test",
            Map.of("command", "mvn test"),
            NOW,
            NOW
        ));
        String output = String.join("\n", java.util.stream.IntStream.rangeClosed(1, 100)
            .mapToObj(index -> "line " + index)
            .toList());
        reducer.reduce(new ToolProgressEvent(
            "ses_1",
            "toolu_bash",
            ToolProgress.output("stdout", output),
            NOW.plusMillis(1)
        ));
        TuiViewModel view = new TuiViewModel(
            reducer.view().blocks(),
            new StatusBarState("ses_1", "gpt-5.4", "execute", "default"),
            List.of(),
            Optional.empty(),
            Optional.empty()
        );
        TuiRenderer renderer = new TuiRenderer();

        TuiRenderFrame collapsed = renderer.renderSurface(
            view,
            view.blocks(),
            new TuiLayout(80, 120),
            "",
            -1,
            List.of(),
            false
        );
        TuiRenderFrame expanded = renderer.renderSurface(
            view,
            view.blocks(),
            new TuiLayout(80, 120),
            "",
            -1,
            List.of(),
            true
        );

        assertTrue(renderedContentLines(collapsed).size() <= 5);
        assertTrue(renderedContentLines(expanded).size() <= 40);
        assertTrue(collapsed.lines().stream().anyMatch(line -> line.contains("earlier lines")));
        assertTrue(expanded.lines().stream().anyMatch(line -> line.contains("earlier lines")));
    }

    private static MessageDeltaEvent delta(
        String messageId,
        String blockId,
        ContentBlockKind blockKind,
        String delta,
        boolean isFinal
    ) {
        return new MessageDeltaEvent(
            "ses_1",
            messageId,
            MessageRole.ASSISTANT,
            blockKind == ContentBlockKind.THINKING ? MessageKind.THINKING : MessageKind.TEXT,
            blockId,
            blockKind,
            delta,
            isFinal,
            Map.of("turnId", "turn_1"),
            NOW
        );
    }

    private static List<String> renderedTranscript(TuiViewModel view) {
        TuiRenderFrame frame = new TuiRenderer().renderSurface(
            view,
            view.blocks(),
            new TuiLayout(80, 20),
            "",
            -1,
            List.of(),
            false
        );
        return renderedContentLines(frame).stream()
            .map(line -> line.replaceAll("\\u001B\\[[;\\d]*m", ""))
            .toList();
    }

    private static List<String> renderedContentLines(TuiRenderFrame frame) {
        int inputStart = java.util.stream.IntStream.range(0, frame.lines().size())
            .filter(index -> frame.lines().get(index).contains("─".repeat(10)))
            .findFirst()
            .orElse(frame.lines().size() - 1);
        return frame.lines().subList(0, inputStart).stream()
            .filter(line -> !line.isBlank())
            .filter(line -> !line.contains("┄"))
            .toList();
    }

    private static PermissionDecision decision(
        PermissionBehavior behavior,
        String message,
        Optional<PermissionUpdate> update
    ) {
        return new PermissionDecision(
            behavior,
            PermissionDecisionReason.TOOL_SPECIFIC,
            message,
            update,
            Map.of("rule", "bash:npm test")
        );
    }

}
