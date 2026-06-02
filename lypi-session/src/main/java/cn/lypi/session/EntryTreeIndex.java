package cn.lypi.session;

import cn.lypi.contracts.session.SessionEntry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class EntryTreeIndex {
    private final Map<String, SessionEntry> byId = new LinkedHashMap<>();

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
        if (byId.isEmpty()) {
            return null;
        }
        String leafId = null;
        for (String id : byId.keySet()) {
            leafId = id;
        }
        return leafId;
    }

    void add(SessionEntry entry) {
        validateAppend(entry);
        byId.put(entry.id(), entry);
    }

    void validateAppend(SessionEntry entry) {
        if (byId.containsKey(entry.id())) {
            throw new SessionEngineException("Session entry already exists: " + entry.id());
        }
        String parentId = entry.parentId();
        if (parentId != null && !byId.containsKey(parentId)) {
            throw new SessionEngineException("Parent session entry does not exist: " + parentId);
        }
    }

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
