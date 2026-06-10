package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.common.ToolProgress;
import cn.lypi.contracts.context.ContentReplacementRecord;
import cn.lypi.contracts.context.ContentBlockKind;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
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
import cn.lypi.contracts.security.PermissionOption;
import cn.lypi.contracts.security.PermissionOptionKind;
import cn.lypi.contracts.security.PermissionRule;
import cn.lypi.contracts.security.PermissionRuleSource;
import cn.lypi.contracts.security.PermissionRuleValue;
import cn.lypi.contracts.security.PermissionUpdate;
import cn.lypi.contracts.session.SessionView;
import cn.lypi.contracts.tool.ToolExecutionStatus;
import cn.lypi.contracts.tool.ToolOutputRef;
import cn.lypi.contracts.tool.ToolResultSummary;
import cn.lypi.contracts.tui.PermissionPromptView;
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
import org.junit.jupiter.api.Test;

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
        assertEquals("Run shell", tool.label());
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
        assertEquals(List.of("allow_once", "remember", "escape_cancel"),
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
    void restoredSessionPointerDoesNotSmuggleRecentFilesPermissionsOrToolsIntoView() {
        TuiEventReducer reducer = TuiEventReducer.fromSessionView(new SessionView("ses_1", "leaf_right"));

        TuiViewModel view = reducer.view();

        assertTrue(view.blocks().isEmpty());
        assertTrue(view.files().isEmpty());
        assertTrue(view.permissionPrompt().isEmpty());
        assertTrue(view.diffView().isEmpty());
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
