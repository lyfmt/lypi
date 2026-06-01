package cn.lypi.session;

import cn.lypi.contracts.session.ForkRequest;
import cn.lypi.contracts.session.SessionEntry;
import cn.lypi.contracts.session.SessionHandle;
import cn.lypi.contracts.session.SessionHeader;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

final class ForkService {
    private final JsonlSessionStore store;
    private final Clock clock;

    ForkService(JsonlSessionStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    SessionHandle fork(ForkRequest request, EntryTreeIndex sourceIndex) {
        List<SessionEntry> path = new ArrayList<>(sourceIndex.pathToRoot(request.forkPointEntryId()));
        Collections.reverse(path);
        String forkSessionId = "ses_" + UUID.randomUUID().toString().replace("-", "");
        SessionHeader header = new SessionHeader(
            "session",
            1,
            forkSessionId,
            request.targetCwd(),
            Optional.of(request.sourceSessionId()),
            Instant.now(clock)
        );
        store.create(header);
        EntryTreeIndex forkIndex = new EntryTreeIndex(List.of());
        for (SessionEntry entry : path) {
            forkIndex.add(entry);
            store.append(forkSessionId, entry);
        }
        return new SessionHandle(
            forkSessionId,
            store.sessionFile(forkSessionId),
            forkIndex.leafId(),
            forkIndex.byId()
        );
    }
}
