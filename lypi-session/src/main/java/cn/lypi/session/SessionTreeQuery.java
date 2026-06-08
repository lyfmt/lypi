package cn.lypi.session;

import cn.lypi.contracts.session.SessionHeader;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/**
 * 查询 session 之间的父子关系。
 *
 * NOTE: 查询只基于持久化 header，不依赖运行中 AgentCenter，因此 closed child session 仍可检查。
 */
public final class SessionTreeQuery {
    private final JsonlSessionStore store;

    public SessionTreeQuery(Path cwd) {
        this.store = new JsonlSessionStore(cwd);
    }

    /**
     * 返回指定 parent session 的 child sessions。
     */
    public List<ChildSessionView> children(String parentSessionId) {
        if (parentSessionId == null || parentSessionId.isBlank()) {
            return List.of();
        }
        return store.headers().stream()
            .filter(header -> header.parentSessionId().filter(parentSessionId::equals).isPresent())
            .sorted(Comparator.comparing(SessionHeader::timestamp))
            .map(this::view)
            .toList();
    }

    private ChildSessionView view(SessionHeader header) {
        return new ChildSessionView(
            header.id(),
            header.cwd(),
            header.parentSessionId(),
            header.parentSpawnEntryId(),
            header.depth(),
            header.agentName(),
            header.agentRole()
        );
    }
}
