package cn.lypi.contracts.session;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.time.Instant;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = MessageEntry.class, name = "message"),
    @JsonSubTypes.Type(value = ModelChangeEntry.class, name = "model_change"),
    @JsonSubTypes.Type(value = ThinkingChangeEntry.class, name = "thinking_change"),
    @JsonSubTypes.Type(value = ModeChangeEntry.class, name = "mode_change"),
    @JsonSubTypes.Type(value = PermissionModeChangeEntry.class, name = "permission_mode_change"),
    @JsonSubTypes.Type(value = CompactionEntry.class, name = "compaction"),
    @JsonSubTypes.Type(value = BranchSummaryEntry.class, name = "branch_summary"),
    @JsonSubTypes.Type(value = CustomEntry.class, name = "custom"),
    @JsonSubTypes.Type(value = CustomMessageEntry.class, name = "custom_message"),
    @JsonSubTypes.Type(value = LabelEntry.class, name = "label"),
    @JsonSubTypes.Type(value = SessionInfoEntry.class, name = "session_info"),
    @JsonSubTypes.Type(value = PermissionPendingEntry.class, name = "permission_pending"),
    @JsonSubTypes.Type(value = PermissionDecisionEntry.class, name = "permission_decision")
})
public interface SessionEntry {
    String id();

    String parentId();

    Instant timestamp();
}
