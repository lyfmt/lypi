package cn.lypi.contracts.tui;

public record TuiMessageBlock(
    String blockId,
    String messageId,
    String role,
    String content,
    boolean streaming
) implements TuiBlock {
    public TuiMessageBlock {
        role = role == null ? "assistant" : role;
        content = content == null ? "" : content;
    }

    @Override
    public TuiBlockKind kind() {
        return TuiBlockKind.MESSAGE;
    }
}
