package cn.lypi.boot.runtime;

import cn.lypi.contracts.common.IdGenerator;
import cn.lypi.contracts.context.ContextBudget;
import cn.lypi.contracts.event.EventBus;
import cn.lypi.contracts.event.SessionStartEvent;
import cn.lypi.contracts.event.SessionStateEvent;
import cn.lypi.contracts.runtime.SessionManagerPort;
import cn.lypi.contracts.session.SessionContext;
import cn.lypi.contracts.session.SessionHandle;
import cn.lypi.contracts.tui.NewSessionController;
import cn.lypi.contracts.tui.SessionRuntimeState;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

final class DefaultNewSessionController implements NewSessionController {
    private final Path cwd;
    private final SessionManagerPort sessionManager;
    private final EventBus events;
    private final Clock clock;
    private final IdGenerator ids;

    DefaultNewSessionController(Path cwd, SessionManagerPort sessionManager, EventBus events) {
        this(cwd, sessionManager, events, Clock.systemUTC(), IdGenerator.random());
    }

    DefaultNewSessionController(
        Path cwd,
        SessionManagerPort sessionManager,
        EventBus events,
        Clock clock,
        IdGenerator ids
    ) {
        this.cwd = cwd == null ? Path.of(".") : cwd;
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager must not be null");
        this.events = Objects.requireNonNull(events, "events must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
    }

    /**
     * 创建新的空 session。
     */
    @Override
    public SessionRuntimeState createNewSession() {
        Instant now = Instant.now(clock);
        SessionHandle handle = sessionManager.openOrCreate(ids.sessionId());
        events.publish(new SessionStartEvent(handle.sessionId(), now));
        SessionContext context = sessionManager.context(handle.leafId());
        events.publish(new SessionStateEvent(
            handle.sessionId(),
            handle.leafId(),
            context.model(),
            context.thinkingLevel(),
            context.mode(),
            context.permissionRuntimeState(),
            Instant.now(clock)
        ));
        return new SessionRuntimeState(
            handle.sessionId(),
            cwd,
            handle.leafId(),
            context.model(),
            context.thinkingLevel(),
            context.mode(),
            context.permissionRuntimeState(),
            new ContextBudget(0, 128_000, 100_000, 8_192, 16_384, 0L, 0L, BigDecimal.ZERO),
            List.of(),
            false,
            false,
            false,
            false
        );
    }
}
