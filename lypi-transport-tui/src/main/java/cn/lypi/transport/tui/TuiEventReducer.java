package cn.lypi.transport.tui;

import cn.lypi.contracts.context.ContentBlockKind;
import cn.lypi.contracts.context.MessageRole;
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
import cn.lypi.contracts.event.ProviderFallbackEndEvent;
import cn.lypi.contracts.event.ProviderFallbackStartEvent;
import cn.lypi.contracts.event.RetryEndEvent;
import cn.lypi.contracts.event.RetryStartEvent;
import cn.lypi.contracts.event.SessionStartEvent;
import cn.lypi.contracts.event.SessionStateEvent;
import cn.lypi.contracts.event.ToolEndEvent;
import cn.lypi.contracts.event.ToolProgressEvent;
import cn.lypi.contracts.event.ToolStartEvent;
import cn.lypi.contracts.event.TurnEndEvent;
import cn.lypi.contracts.event.TurnStartEvent;
import cn.lypi.contracts.security.AdditionalPermissionProfile;
import cn.lypi.contracts.security.FileSystemPermissionEntry;
import cn.lypi.contracts.security.FileSystemPermissionPolicy;
import cn.lypi.contracts.security.NetworkPermissionPolicy;
import cn.lypi.contracts.session.SessionView;
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
import java.time.Instant;

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
            case TurnStartEvent start -> state.turnStarted(start.turnId(), start.startedAt(), start.timestamp());
            case TurnEndEvent end -> state.turnEnded(end.durationMillis());
            case RetryStartEvent start -> state.retryStarted(start.attempt(), start.reason());
            case RetryEndEvent ignored -> state.retryEnded();
            case ProviderFallbackStartEvent start -> state.providerFallbackStarted(
                start.fromMode(),
                start.toMode(),
                start.reason()
            );
            case ProviderFallbackEndEvent end -> state.providerFallbackEnded(end.toMode(), end.success());
            case CompactStartEvent start -> state.compactStarted(start.kind());
            case CompactEndEvent ignored -> state.compactEnded();
            case InterruptEvent interrupt -> {
                state.clearPermissionPrompt();
                state.interrupted(interrupt.reason());
            }
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

    void observeRuntimeAt(Instant observedAt) {
        state.observeTurnAt(observedAt);
    }

    boolean hasActiveTurn() {
        return state.hasActiveTurn();
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
                    int toolIndex = state.toolIndex(toolUseId).orElse(index);
                    putOrAdd(toolIndex, block);
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
        String label = firstNonBlank(event.inputSummary(), event.displayTitle(), event.toolName());
        String details = state.startToolProgress(
            event.toolUseId(),
            metadataString(event.inputMetadata(), "preview", "")
        );
        TuiToolBlock block = new TuiToolBlock(
            "tool:" + event.toolUseId(),
            event.parentMessageId(),
            event.toolUseId(),
            event.toolName(),
            TuiToolState.RUNNING,
            label,
            details,
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
            String details = state.appendToolProgress(
                event.toolUseId(),
                current.details(),
                event.progress()
            );
            state.putBlock(index, new TuiToolBlock(
                current.blockId(),
                current.messageId(),
                current.toolUseId(),
                current.toolName(),
                TuiToolState.RUNNING,
                current.label(),
                details,
                true
            ));
        });
    }

    private void reduceToolEnd(ToolEndEvent event) {
        state.toolIndex(event.toolUseId()).ifPresent(index -> {
            TuiToolBlock current = (TuiToolBlock) state.blocks().get(index);
            TuiToolState toolState = TuiTranscriptProjector.stateFor(event.status());
            String details = state.completeToolProgress(
                event.toolUseId(),
                current.details(),
                event
            );
            state.putBlock(index, new TuiToolBlock(
                current.blockId(),
                current.messageId(),
                current.toolUseId(),
                current.toolName(),
                toolState,
                current.label(),
                details,
                false
            ));
        });
        state.toolEnded(event.toolUseId());
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
        String rule = permissionRuleLine(event);
        if (rule.isBlank()) {
            rule = event.defaultOptionId();
        }
        state.permissionPrompt(new PermissionPromptView(
            event.requestId(),
            event.toolUseId(),
            permissionReasonLine(event),
            rule,
            event.defaultOptionId(),
            event.cancelOptionId(),
            event.options(),
            event.defaultOptionId()
        ));
    }

    private String permissionReasonLine(PermissionRequestEvent event) {
        StringBuilder reason = new StringBuilder();
        if (event.approvalKind() == cn.lypi.contracts.security.ApprovalKind.REQUEST_PERMISSIONS) {
            appendLine(reason, event.approvalKind().name());
        }
        appendLine(reason, event.message());
        if (event.approvalKind() == cn.lypi.contracts.security.ApprovalKind.REQUEST_PERMISSIONS) {
            String decisions = PermissionOverlay.formatReviewDecisions(event.availableDecisions());
            if (!decisions.isBlank()) {
                appendLine(reason, "decisions: " + decisions);
            }
        }
        return reason.toString();
    }

    private String permissionRuleLine(PermissionRequestEvent event) {
        StringBuilder rule = new StringBuilder();
        appendLine(rule, PermissionOverlay.formatRule(event.policyDecision()));
        if (event.approvalKind() == cn.lypi.contracts.security.ApprovalKind.REQUEST_PERMISSIONS) {
            event.additionalPermissions()
                .map(this::formatAdditionalPermissions)
                .filter(text -> !text.isBlank())
                .ifPresent(text -> appendLine(rule, text));
        }
        return rule.toString();
    }

    private String formatAdditionalPermissions(AdditionalPermissionProfile profile) {
        StringBuilder builder = new StringBuilder();
        profile.fileSystem()
            .ifPresent(policy -> appendLine(builder, formatFileSystemPermissionPolicy(policy)));
        profile.network()
            .map(NetworkPermissionPolicy::mode)
            .ifPresent(mode -> appendLine(builder, "network=" + mode.name()));
        return builder.toString();
    }

    private String formatFileSystemPermissionPolicy(FileSystemPermissionPolicy policy) {
        StringBuilder builder = new StringBuilder("filesystem=" + policy.kind().name());
        for (FileSystemPermissionEntry entry : policy.entries()) {
            builder.append('\n')
                .append(entry.access().name())
                .append(' ')
                .append(entry.path().value());
        }
        return builder.toString();
    }

    private void reducePermissionDecision(PermissionDecisionEvent event) {
        state.clearPermissionPrompt();
    }

    private void reduceError(ErrorEvent event) {
        state.providerErrorObserved();
        state.addBlock(new TuiErrorBlock(
            event.errorId(),
            event.message()
        ));
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

}
