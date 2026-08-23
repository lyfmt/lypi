package cn.lycode.contracts.tui;

import cn.lycode.contracts.session.SessionEntry;
import java.util.List;

public record SessionTreeNodeView(
    SessionEntry entry,
    List<SessionTreeNodeView> children
) {
    public SessionTreeNodeView {
        children = children == null ? List.of() : List.copyOf(children);
    }
}
