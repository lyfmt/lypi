package cn.lypi.contracts.tui;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = TuiMessageBlock.class, name = "message"),
    @JsonSubTypes.Type(value = TuiThinkingBlock.class, name = "thinking"),
    @JsonSubTypes.Type(value = TuiToolBlock.class, name = "tool"),
    @JsonSubTypes.Type(value = TuiErrorBlock.class, name = "error")
})
public sealed interface TuiBlock permits TuiMessageBlock, TuiThinkingBlock, TuiToolBlock, TuiErrorBlock {
    /**
     * 返回 TUI 基础块类型。
     */
    TuiBlockKind kind();

    /**
     * 返回块稳定标识。
     */
    String blockId();
}
