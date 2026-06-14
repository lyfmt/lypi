package cn.lypi.contracts.tui;

import java.util.List;

public record SessionBranchTreeView(
    String sessionId,
    String currentLeafId,
    List<SessionTreeNodeView> roots
) {
    public SessionBranchTreeView {
        roots = roots == null ? List.of() : List.copyOf(roots);
    }
}
