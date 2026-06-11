package cn.lypi.transport.tui;

import cn.lypi.contracts.context.ContentBlockKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.common.ToolProgress;
import cn.lypi.contracts.event.AgentEvent;
import cn.lypi.contracts.event.CompactEndEvent;
import cn.lypi.contracts.event.CompactStartEvent;
import cn.lypi.contracts.event.ErrorEvent;
import cn.lypi.contracts.event.InterruptEvent;
import cn.lypi.contracts.event.MessageBlockSnapshot;
import cn.lypi.contracts.event.MessageDeltaEvent;
import cn.lypi.contracts.event.MessageEndEvent;
import cn.lypi.contracts.event.MessageStartEvent;
import cn.lypi.contracts.event.PermissionDecisionEvent;
import cn.lypi.contracts.event.PermissionRequestEvent;
import cn.lypi.contracts.event.RetryEndEvent;
import cn.lypi.contracts.event.RetryStartEvent;
import cn.lypi.contracts.event.SessionStartEvent;
import cn.lypi.contracts.event.SessionStateEvent;
import cn.lypi.contracts.event.ToolEndEvent;
import cn.lypi.contracts.event.ToolProgressEvent;
import cn.lypi.contracts.event.ToolStartEvent;
import cn.lypi.contracts.event.TurnEndEvent;
import cn.lypi.contracts.event.TurnStartEvent;
import cn.lypi.contracts.session.SessionView;
import cn.lypi.contracts.tool.ToolResultSummary;
import cn.lypi.contracts.tool.ToolExecutionStatus;
import cn.lypi.contracts.tool.ToolOutputRef;
import cn.lypi.contracts.tui.DiffView;
import cn.lypi.contracts.tui.PermissionPromptView;
import cn.lypi.contracts.tui.SessionRuntimeState;
import cn.lypi.contracts.tui.TuiBlock;
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
     * 从运行时状态初始化首屏。
     */
    public static TuiEventReducer fromRuntimeState(SessionRuntimeState runtimeState) {
        TuiEventReducer reducer = new TuiEventReducer(new TuiRenderState());
        reducer.configureRuntimeState(runtimeState);
        return reducer;
    }

    /**
     * 从运行时状态初始化首屏状态栏。
     */
    public static TuiEventReducer withRuntimeState(SessionRuntimeState runtimeState) {
        return fromRuntimeState(runtimeState);
    }

    /**
     * 更新运行时状态栏投影。
     */
    public void configureRuntimeState(SessionRuntimeState runtimeState) {
        state.configure(runtimeState);
    }

    /**
     * 消费一条语义事件并更新 TUI view state。
     */
    public TuiViewModel reduce(AgentEvent event) {
        switch (event) {
            case MessageStartEvent start -> {
                reduceMessageStart(start);
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
            case TurnStartEvent start -> state.turnStarted(start.turnId());
            case TurnEndEvent ignored -> state.turnEnded();
            case RetryStartEvent start -> state.retryStarted(start.attempt(), start.reason());
            case RetryEndEvent ignored -> state.retryEnded();
            case CompactStartEvent start -> state.compactStarted(start.kind());
            case CompactEndEvent ignored -> state.compactEnded();
            case InterruptEvent interrupt -> state.interrupted(interrupt.reason());
            case SessionStartEvent start -> state.sessionStarted(start.sessionId());
            case SessionStateEvent sessionState -> state.sessionStateChanged(sessionState);
            default -> {
                return view();
            }
        }
        return view();
    }

    public TuiViewModel view() {
        return state.view();
    }

    public void showDiff(DiffView diffView) {
        state.diffView(diffView);
    }

    public void clearDiff() {
        state.clearDiffView();
    }

    private void reduceMessageStart(MessageStartEvent event) {
        if (!shouldCreateTextPlaceholder(event)) {
            return;
        }
        String blockId = event.messageId() + ":text:0";
        if (state.blockIndex(blockId).isPresent()) {
            return;
        }
        state.addBlock(new TuiMessageBlock(blockId, event.messageId(), roleName(event.role()), "", true));
    }

    private boolean shouldCreateTextPlaceholder(MessageStartEvent event) {
        if (event.role() == MessageRole.USER) {
            return true;
        }
        if (event.role() != MessageRole.ASSISTANT) {
            return false;
        }
        if (event.kind() != cn.lypi.contracts.context.MessageKind.TEXT) {
            return false;
        }
        Object provisional = event.metadata().get("kindProvisional");
        return !Boolean.TRUE.equals(provisional);
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
            return;
        }
        if (event.blockKind() == ContentBlockKind.TOOL_CALL) {
            upsertPendingToolBlock(event);
        }
    }

    private void upsertMessageBlock(MessageDeltaEvent event) {
        String role = roleName(event.role());
        int index = state.blockIndex(event.blockId())
            .or(() -> emptyStreamingTextPlaceholderIndex(event.messageId()))
            .orElse(-1);
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
            event.blockId(),
            event.messageId(),
            role,
            current.content() + event.delta(),
            !event.isFinal()
        ));
    }

    private java.util.Optional<Integer> emptyStreamingTextPlaceholderIndex(String messageId) {
        for (int index = 0; index < state.blocks().size(); index++) {
            if (state.blocks().get(index) instanceof TuiMessageBlock block
                && messageId.equals(block.messageId())
                && block.streaming()
                && block.content().isBlank()) {
                return java.util.Optional.of(index);
            }
        }
        return java.util.Optional.empty();
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

    private void upsertPendingToolBlock(MessageDeltaEvent event) {
        String toolUseId = metadataString(event, "toolUseId", event.blockId());
        String toolName = metadataString(event, "toolName", "unknown");
        String label = metadataString(event, "inputSummary", toolName);
        TuiToolBlock block = new TuiToolBlock(
            "tool:" + toolUseId,
            event.messageId(),
            toolUseId,
            toolName,
            TuiToolState.PENDING,
            label,
            true
        );
        int index = state.toolIndex(toolUseId).orElse(-1);
        if (index < 0) {
            state.addBlock(block);
            state.putToolIndex(toolUseId, state.blocks().size() - 1);
        } else {
            state.putBlock(index, block);
        }
    }

    private void reduceMessageEnd(MessageEndEvent event) {
        upsertSnapshotBlocks(event);
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
                case TuiToolBlock block when event.messageId().equals(block.messageId())
                    && block.state() == TuiToolState.PENDING
                    && block.active() -> state.putBlock(
                    i,
                    new TuiToolBlock(
                        block.blockId(),
                        block.messageId(),
                        block.toolUseId(),
                        block.toolName(),
                        block.state(),
                        block.label(),
                        false
                    )
                );
                default -> {
                }
            }
        }
    }

    private void upsertSnapshotBlocks(MessageEndEvent event) {
        for (MessageBlockSnapshot snapshot : event.blocks()) {
            String blockId = firstNonBlank(snapshot.blockId(), event.messageId() + ":" + snapshot.blockKind().name().toLowerCase());
            int index = snapshotBlockIndex(event, snapshot, blockId);
            switch (snapshot.blockKind()) {
                case TEXT -> putOrAdd(index, new TuiMessageBlock(
                    blockId,
                    event.messageId(),
                    roleName(event.role()),
                    snapshot.text(),
                    false
                ));
                case THINKING -> putOrAdd(index, new TuiThinkingBlock(
                    blockId,
                    event.messageId(),
                    snapshot.text(),
                    false,
                    false
                ));
                case ERROR -> putOrAdd(index, new TuiErrorBlock(blockId, snapshot.text()));
                case TOOL_CALL -> {
                    String toolUseId = metadataString(snapshot, "toolUseId", blockId);
                    String toolName = metadataString(snapshot, "toolName", "unknown");
                    String label = metadataString(snapshot, "inputSummary", firstNonBlank(snapshot.text(), toolName));
                    TuiToolBlock block = new TuiToolBlock(
                        "tool:" + toolUseId,
                        event.messageId(),
                        toolUseId,
                        toolName,
                        TuiToolState.PENDING,
                        label,
                        false
                    );
                    putOrAdd(index, block);
                }
                default -> {
                }
            }
        }
        state.rebuildIndexes();
    }

    private int snapshotBlockIndex(MessageEndEvent event, MessageBlockSnapshot snapshot, String blockId) {
        java.util.Optional<Integer> existing = state.blockIndex(blockId);
        if (existing.isPresent()) {
            return existing.orElseThrow();
        }
        if (snapshot.blockKind() == ContentBlockKind.TEXT) {
            return emptyStreamingTextPlaceholderIndex(event.messageId()).orElse(-1);
        }
        if (snapshot.blockKind() == ContentBlockKind.TOOL_CALL) {
            String toolUseId = metadataString(snapshot, "toolUseId", "");
            if (!toolUseId.isBlank()) {
                java.util.Optional<Integer> toolIdx = state.toolIndex(toolUseId);
                if (toolIdx.isPresent()) {
                    return toolIdx.orElseThrow();
                }
            }
        }
        return -1;
    }

    private void putOrAdd(int index, TuiBlock block) {
        if (index < 0) {
            state.addBlock(block);
            return;
        }
        state.putBlock(index, block);
    }

    private void reduceToolStart(ToolStartEvent event) {
        String label = firstNonBlank(event.displayTitle(), event.inputSummary(), event.toolName());
        TuiToolBlock block = new TuiToolBlock(
            "tool:" + event.toolUseId(),
            event.parentMessageId(),
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
        state.toolStarted(event.toolUseId());
    }

    private void reduceToolProgress(ToolProgressEvent event) {
        state.toolIndex(event.toolUseId()).ifPresent(index -> {
            TuiToolBlock current = (TuiToolBlock) state.blocks().get(index);
            state.putBlock(index, new TuiToolBlock(
                current.blockId(),
                current.messageId(),
                current.toolUseId(),
                current.toolName(),
                TuiToolState.RUNNING,
                current.label(),
                appendDetail(current.details(), progressDetail(event.progress())),
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
                current.messageId(),
                current.toolUseId(),
                current.toolName(),
                toolState,
                current.label(),
                appendDetail(current.details(), endDetail(event)),
                false
            ));
        });
        state.toolEnded(event.toolUseId());
    }

    private String progressDetail(ToolProgress progress) {
        if (progress == null) {
            return "";
        }
        return switch (progress.kind()) {
            case OUTPUT -> firstNonBlank(progress.stream(), "output") + ": " + firstNonBlank(progress.delta(), "");
            case PHASE -> firstNonBlank(progress.phase(), progress.title(), progress.detail());
            case STATUS -> firstNonBlank(progress.title(), "") + suffix(progress.detail());
            case COUNTER -> firstNonBlank(progress.title(), "progress") + " " + progress.current() + "/" + progress.total();
            case PERCENT -> firstNonBlank(progress.title(), "progress") + " " + percentLabel(progress.percent());
            case CUSTOM -> firstNonBlank(progress.title(), progress.metadata().toString());
        };
    }

    private String endDetail(ToolEndEvent event) {
        StringBuilder detail = new StringBuilder();
        if (event.exitCode() != null) {
            detail.append("exit ").append(event.exitCode());
        }
        ToolResultSummary summary = event.resultSummary();
        if (summary != null) {
            appendLine(detail, firstNonBlank(summary.summary(), summary.title()));
        }
        String preview = preview(event.resultRef(), summary);
        if (!preview.isBlank()) {
            appendLine(detail, preview);
        }
        return detail.toString();
    }

    private String preview(ToolOutputRef resultRef, ToolResultSummary summary) {
        String refPreview = metadataString(resultRef == null ? null : resultRef.metadata(), "preview", "");
        if (!refPreview.isBlank()) {
            return refPreview;
        }
        if (summary == null) {
            return "";
        }
        return metadataString(summary.metadata(), "preview", "");
    }

    private String appendDetail(String current, String addition) {
        String safeAddition = addition == null ? "" : addition.strip();
        if (safeAddition.isBlank()) {
            return current == null ? "" : current;
        }
        String safeCurrent = current == null ? "" : current.strip();
        if (safeCurrent.isBlank()) {
            return safeAddition;
        }
        return safeCurrent + "\n" + safeAddition;
    }

    private void appendLine(StringBuilder builder, String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append('\n');
        }
        builder.append(line);
    }

    private void reducePermissionRequest(PermissionRequestEvent event) {
        String rule = PermissionOverlay.formatRule(event.policyDecision());
        if (rule.isBlank()) {
            rule = event.defaultOptionId();
        }
        state.permissionPrompt(new PermissionPromptView(
            event.requestId(),
            event.toolUseId(),
            event.message(),
            rule,
            event.defaultOptionId(),
            event.cancelOptionId(),
            event.options(),
            event.defaultOptionId()
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

    private String metadataString(MessageDeltaEvent event, String key, String fallback) {
        Object value = event.metadata().get(key);
        if (value == null) {
            return fallback;
        }
        String text = value.toString();
        return text.isBlank() ? fallback : text;
    }

    private String metadataString(MessageBlockSnapshot snapshot, String key, String fallback) {
        Object value = snapshot.metadata().get(key);
        if (value == null) {
            return fallback;
        }
        String text = value.toString();
        return text.isBlank() ? fallback : text;
    }

    private String metadataString(java.util.Map<String, Object> metadata, String key, String fallback) {
        if (metadata == null) {
            return fallback;
        }
        Object value = metadata.get(key);
        if (value == null) {
            return fallback;
        }
        String text = value.toString();
        return text.isBlank() ? fallback : text;
    }

    private String suffix(String value) {
        return value == null || value.isBlank() ? "" : " " + value;
    }

    private String percentLabel(Double percent) {
        if (percent == null) {
            return "";
        }
        if (percent % 1 == 0) {
            return percent.intValue() + "%";
        }
        return String.format(java.util.Locale.ROOT, "%.1f%%", percent);
    }
}
