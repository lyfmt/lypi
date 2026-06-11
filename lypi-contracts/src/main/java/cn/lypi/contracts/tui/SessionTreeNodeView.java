package cn.lypi.contracts.tui;

import cn.lypi.contracts.session.SessionEntry;
import java.util.List;

public record SessionTreeNodeView(
    SessionEntry entry,
    List<SessionTreeNodeView> children
) {
    public SessionTreeNodeView {
        children = children == null ? List.of() : List.copyOf(children);
    }
}
