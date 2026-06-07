package cn.lypi.contracts.tui;

public record TuiThinkingBlock(
    String blockId,
    String messageId,
    String content,
    boolean streaming,
    boolean collapsed
) implements TuiBlock {
    public TuiThinkingBlock {
        content = content == null ? "" : content;
    }

    @Override
    public TuiBlockKind kind() {
        return TuiBlockKind.THINKING;
    }
}
