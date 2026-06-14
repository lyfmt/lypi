package cn.lypi.session;

import cn.lypi.contracts.session.SessionEntry;
import cn.lypi.contracts.tui.SessionBranchTreeView;
import cn.lypi.contracts.tui.SessionTreeNodeView;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 查询单个 session 的 entry tree。
 */
public final class SessionBranchTreeQuery {
    private final JsonlSessionStore store;

    public SessionBranchTreeQuery(Path cwd) {
        this.store = new JsonlSessionStore(cwd);
    }

    /**
     * 返回指定 session 的树形视图。
     */
    public SessionBranchTreeView tree(String sessionId) {
        SessionFile file = store.read(sessionId);
        Map<String, MutableNode> byId = new LinkedHashMap<>();
        for (SessionEntry entry : file.entries()) {
            byId.put(entry.id(), new MutableNode(entry));
        }
        List<MutableNode> roots = new ArrayList<>();
        for (MutableNode node : byId.values()) {
            String parentId = node.entry().parentId();
            if (parentId != null && byId.containsKey(parentId)) {
                byId.get(parentId).children().add(node);
            } else {
                roots.add(node);
            }
        }
        String leafId = SessionLeafSelector.latestNavigableLeaf(file.entries());
        return new SessionBranchTreeView(sessionId, leafId, roots.stream().map(this::view).toList());
    }

    private SessionTreeNodeView view(MutableNode node) {
        return new SessionTreeNodeView(node.entry(), node.children().stream().map(this::view).toList());
    }

    private record MutableNode(SessionEntry entry, List<MutableNode> children) {
        private MutableNode(SessionEntry entry) {
            this(entry, new ArrayList<>());
        }
    }
}
