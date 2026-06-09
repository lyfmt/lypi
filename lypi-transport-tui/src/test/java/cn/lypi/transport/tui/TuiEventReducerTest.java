package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.common.ToolProgress;
import cn.lypi.contracts.context.ContentBlockKind;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.event.ErrorEvent;
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
import cn.lypi.contracts.session.SessionView;
import cn.lypi.contracts.tool.ToolExecutionStatus;
import cn.lypi.contracts.tool.ToolOutputRef;
import cn.lypi.contracts.tool.ToolResultSummary;
import cn.lypi.contracts.tui.TuiBlock;
import cn.lypi.contracts.tui.TuiBlockKind;
import cn.lypi.contracts.tui.TuiErrorBlock;
import cn.lypi.contracts.tui.TuiMessageBlock;
import cn.lypi.contracts.tui.TuiThinkingBlock;
import cn.lypi.contracts.tui.TuiToolBlock;
import cn.lypi.contracts.tui.TuiToolState;
import cn.lypi.contracts.tui.TuiViewModel;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TuiEventReducerTest {
    private static final Instant NOW = Instant.parse("2026-06-07T09:00:00Z");

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
        assertEquals("Bash", toolAfterStart.label());
        assertEquals(TuiToolState.RUNNING, toolAfterStart.state());
        assertTrue(toolAfterStart.active());

        TuiToolBlock tool = assertInstanceOf(TuiToolBlock.class, blocks.get(2));
        assertEquals(TuiBlockKind.TOOL, tool.kind());
        assertEquals(TuiToolState.DONE, tool.state());
        assertFalse(tool.active());

        assertEquals(3, blocks.size(), "tool progress must not append transcript lines");
        assertEquals("Bash", tool.label(), "tool end must not replace label with result summary");
    }

    @Test
    void permissionRequestAndDecisionControlOverlayWithoutKeepingToolDetails() {
        TuiEventReducer reducer = new TuiEventReducer();

        reducer.reduce(new PermissionRequestEvent("ses_1", "toolu_1", "Need approval", NOW));

        assertTrue(reducer.view().permissionPrompt().isPresent());
        assertEquals("toolu_1", reducer.view().permissionPrompt().orElseThrow().requestId());
        assertEquals("toolu_1", reducer.view().permissionPrompt().orElseThrow().toolUseId());
        assertEquals("allow_once", reducer.view().permissionPrompt().orElseThrow().defaultOptionId());

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
    void replayInitializationCreatesEmptyFirstScreenWhenOnlySessionPointerExists() {
        TuiEventReducer reducer = TuiEventReducer.fromSessionView(new SessionView("ses_1", "leaf_1"));

        assertTrue(reducer.view().blocks().isEmpty());
        assertTrue(reducer.view().files().isEmpty());
        assertTrue(reducer.view().permissionPrompt().isEmpty());
    }
}
