package cn.lypi.session;

import cn.lypi.contracts.session.SessionEntry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 维护 session entry tree 的内存索引。
 *
 * NOTE: leaf 是内存状态；历史 entry 只通过 append 加入，不原地修改。
 */
final class EntryTreeIndex {
    private final Map<String, SessionEntry> byId = new LinkedHashMap<>();
    private String leafId;

    EntryTreeIndex(List<SessionEntry> entries) {
        for (SessionEntry entry : entries) {
            add(entry);
        }
    }

    Map<String, SessionEntry> byId() {
        return Map.copyOf(byId);
    }

    boolean isEmpty() {
        return byId.isEmpty();
    }

    String leafId() {
        return leafId;
    }

    /**
     * 添加 entry 并将其设为当前 leaf。
     */
    void add(SessionEntry entry) {
        validateAppend(entry);
        byId.put(entry.id(), entry);
        leafId = entry.id();
    }

    /**
     * 切换当前 leaf。
     */
    void switchLeaf(String leafId) {
        if (leafId != null && !byId.containsKey(leafId)) {
            throw new SessionEngineException("Session entry does not exist: " + leafId);
        }
        this.leafId = leafId;
    }

    /**
     * 校验追加 entry 是否满足 tree 约束。
     */
    void validateAppend(SessionEntry entry) {
        if (byId.containsKey(entry.id())) {
            throw new SessionEngineException("Session entry already exists: " + entry.id());
        }
        String parentId = entry.parentId();
        if (parentId != null && !byId.containsKey(parentId)) {
            throw new SessionEngineException("Parent session entry does not exist: " + parentId);
        }
    }

    /**
     * 返回从 leaf 到 root 的路径。
     */
    List<SessionEntry> pathToRoot(String leafId) {
        List<SessionEntry> path = new ArrayList<>();
        String currentId = leafId;
        while (currentId != null) {
            SessionEntry entry = byId.get(currentId);
            if (entry == null) {
                throw new SessionEngineException("Session entry does not exist: " + currentId);
            }
            path.add(entry);
            currentId = entry.parentId();
        }
        return List.copyOf(path);
    }
}
