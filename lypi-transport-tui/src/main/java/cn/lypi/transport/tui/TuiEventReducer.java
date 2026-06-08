package cn.lypi.transport.tui;

import cn.lypi.contracts.context.ContentBlockKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.event.AgentEvent;
import cn.lypi.contracts.event.ErrorEvent;
import cn.lypi.contracts.event.MessageDeltaEvent;
import cn.lypi.contracts.event.MessageEndEvent;
import cn.lypi.contracts.event.MessageStartEvent;
import cn.lypi.contracts.event.PermissionDecisionEvent;
import cn.lypi.contracts.event.PermissionRequestEvent;
import cn.lypi.contracts.event.ToolEndEvent;
import cn.lypi.contracts.event.ToolProgressEvent;
import cn.lypi.contracts.event.ToolStartEvent;
import cn.lypi.contracts.session.SessionView;
import cn.lypi.contracts.tool.ToolExecutionStatus;
import cn.lypi.contracts.tui.PermissionPromptView;
import cn.lypi.contracts.tui.TuiErrorBlock;
import cn.lypi.contracts.tui.TuiMessageBlock;
import cn.lypi.contracts.tui.TuiThinkingBlock;
import cn.lypi.contracts.tui.TuiToolBlock;
import cn.lypi.contracts.tui.TuiToolState;
import cn.lypi.contracts.tui.TuiViewModel;

public final class TuiEventReducer {
    private final TuiRenderState state;

    public TuiEventReducer() {
        this(new TuiRenderState());
    }

    private TuiEventReducer(TuiRenderState state) {
        this.state = state;
    }

    /**
     * 从轻量 session view 初始化首屏。
     */
    public static TuiEventReducer fromSessionView(SessionView sessionView) {
        // NOTE: SessionView 只包含指针信息，不在 reducer 中发明恢复提示内容。
        return new TuiEventReducer(new TuiRenderState());
    }

    /**
     * 消费一条语义事件并更新 TUI view state。
     */
    public TuiViewModel reduce(AgentEvent event) {
        switch (event) {
            case MessageStartEvent ignored -> {
                return view();
            }
            case MessageDeltaEvent delta -> reduceMessageDelta(delta);
            case MessageEndEvent end -> reduceMessageEnd(end);
            case ToolStartEvent start -> reduceToolStart(start);
            case ToolProgressEvent progress -> reduceToolProgress(progress);
            case ToolEndEvent end -> reduceToolEnd(end);
            case PermissionRequestEvent request -> reducePermissionRequest(request);
            case PermissionDecisionEvent decision -> reducePermissionDecision(decision);
            case ErrorEvent error -> reduceError(error);
            default -> {
                return view();
            }
        }
        return view();
    }

    public TuiViewModel view() {
        return state.view();
    }

    private void reduceMessageDelta(MessageDeltaEvent event) {
        if (event.blockKind() == ContentBlockKind.THINKING) {
            upsertThinkingBlock(event);
            return;
        }
        if (event.blockKind() == ContentBlockKind.ERROR) {
            upsertErrorBlock(event);
            return;
        }
        if (event.blockKind() == ContentBlockKind.TEXT) {
            upsertMessageBlock(event);
        }
    }

    private void upsertMessageBlock(MessageDeltaEvent event) {
        String role = roleName(event.role());
        int index = state.blockIndex(event.blockId()).orElse(-1);
        if (index < 0) {
            state.addBlock(new TuiMessageBlock(
                event.blockId(),
                event.messageId(),
                role,
                event.delta(),
                !event.isFinal()
            ));
            return;
        }
        TuiMessageBlock current = (TuiMessageBlock) state.blocks().get(index);
        state.putBlock(index, new TuiMessageBlock(
            current.blockId(),
            current.messageId(),
            current.role(),
            current.content() + event.delta(),
            !event.isFinal()
        ));
    }

    private void upsertErrorBlock(MessageDeltaEvent event) {
        int index = state.blockIndex(event.blockId()).orElse(-1);
        if (index < 0) {
            state.addBlock(new TuiErrorBlock(event.blockId(), event.delta()));
            return;
        }
        TuiErrorBlock current = (TuiErrorBlock) state.blocks().get(index);
        state.putBlock(index, new TuiErrorBlock(
            current.blockId(),
            current.message() + event.delta()
        ));
    }

    private void upsertThinkingBlock(MessageDeltaEvent event) {
        int index = state.blockIndex(event.blockId()).orElse(-1);
        if (index < 0) {
            state.addBlock(new TuiThinkingBlock(
                event.blockId(),
                event.messageId(),
                event.delta(),
                !event.isFinal(),
                false
            ));
            return;
        }
        TuiThinkingBlock current = (TuiThinkingBlock) state.blocks().get(index);
        state.putBlock(index, new TuiThinkingBlock(
            current.blockId(),
            current.messageId(),
            current.content() + event.delta(),
            !event.isFinal(),
            current.collapsed()
        ));
    }

    private void reduceMessageEnd(MessageEndEvent event) {
        for (int i = 0; i < state.blocks().size(); i++) {
            switch (state.blocks().get(i)) {
                case TuiMessageBlock block when event.messageId().equals(block.messageId()) -> state.putBlock(
                    i,
                    new TuiMessageBlock(
                        block.blockId(),
                        block.messageId(),
                        block.role(),
                        block.content(),
                        false
                    )
                );
                case TuiThinkingBlock block when event.messageId().equals(block.messageId()) -> state.putBlock(
                    i,
                    new TuiThinkingBlock(
                        block.blockId(),
                        block.messageId(),
                        block.content(),
                        false,
                        block.collapsed()
                    )
                );
                default -> {
                }
            }
        }
    }

    private void reduceToolStart(ToolStartEvent event) {
        String label = firstNonBlank(event.displayTitle(), event.inputSummary(), event.toolName());
        TuiToolBlock block = new TuiToolBlock(
            "tool:" + event.toolUseId(),
            event.toolUseId(),
            event.toolName(),
            TuiToolState.RUNNING,
            label,
            true
        );
        int index = state.toolIndex(event.toolUseId()).orElse(-1);
        if (index < 0) {
            state.addBlock(block);
            state.putToolIndex(event.toolUseId(), state.blocks().size() - 1);
        } else {
            state.putBlock(index, block);
        }
    }

    private void reduceToolProgress(ToolProgressEvent event) {
        state.toolIndex(event.toolUseId()).ifPresent(index -> {
            TuiToolBlock current = (TuiToolBlock) state.blocks().get(index);
            state.putBlock(index, new TuiToolBlock(
                current.blockId(),
                current.toolUseId(),
                current.toolName(),
                TuiToolState.RUNNING,
                current.label(),
                true
            ));
        });
    }

    private void reduceToolEnd(ToolEndEvent event) {
        state.toolIndex(event.toolUseId()).ifPresent(index -> {
            TuiToolBlock current = (TuiToolBlock) state.blocks().get(index);
            TuiToolState toolState = toolState(event.status());
            state.putBlock(index, new TuiToolBlock(
                current.blockId(),
                current.toolUseId(),
                current.toolName(),
                toolState,
                current.label(),
                false
            ));
        });
    }

    private void reducePermissionRequest(PermissionRequestEvent event) {
        state.permissionPrompt(new PermissionPromptView(
            event.toolUseId(),
            event.message(),
            event.defaultOptionId(),
            event.defaultOptionId(),
            event.cancelOptionId()
        ));
    }

    private void reducePermissionDecision(PermissionDecisionEvent event) {
        state.clearPermissionPrompt();
    }

    private void reduceError(ErrorEvent event) {
        state.addBlock(new TuiErrorBlock(
            event.errorId(),
            event.message()
        ));
    }

    private TuiToolState toolState(ToolExecutionStatus status) {
        if (status == ToolExecutionStatus.CANCELLED) {
            return TuiToolState.CANCELLED;
        }
        if (status == ToolExecutionStatus.FAILED || status == ToolExecutionStatus.TIMED_OUT) {
            return TuiToolState.FAILED;
        }
        return TuiToolState.DONE;
    }

    private String roleName(MessageRole role) {
        if (role == MessageRole.USER) {
            return "user";
        }
        if (role == MessageRole.SYSTEM_LOCAL) {
            return "system";
        }
        if (role == MessageRole.TOOL_RESULT) {
            return "tool";
        }
        return "assistant";
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
