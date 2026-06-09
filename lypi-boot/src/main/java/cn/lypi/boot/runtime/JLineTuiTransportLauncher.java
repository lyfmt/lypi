package cn.lypi.boot.runtime;

import cn.lypi.contracts.event.EventBus;
import cn.lypi.contracts.runtime.AgentCorePort;
import cn.lypi.contracts.runtime.CompactionRuntimePort;
import cn.lypi.contracts.runtime.ResourceRuntimePort;
import cn.lypi.contracts.runtime.SessionManagerPort;
import cn.lypi.contracts.tui.SessionRuntimeState;
import cn.lypi.transport.tui.JLineTuiTransport;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

final class JLineTuiTransportLauncher implements TransportLauncher {
    private final SessionManagerPort sessionManager;
    private final ResourceRuntimePort resourceRuntime;
    private final CompactionRuntimePort compactionRuntime;

    JLineTuiTransportLauncher(
        SessionManagerPort sessionManager,
        ResourceRuntimePort resourceRuntime,
        CompactionRuntimePort compactionRuntime
    ) {
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager must not be null");
        this.resourceRuntime = Objects.requireNonNull(resourceRuntime, "resourceRuntime must not be null");
        this.compactionRuntime = Objects.requireNonNull(compactionRuntime, "compactionRuntime must not be null");
    }

    @Override
    public String name() {
        return "tui";
    }

    @Override
    public void launch(SessionRuntimeState state, AgentCorePort core, EventBus events) {
        try (Terminal terminal = TerminalBuilder.builder().system(true).build();
             JLineTuiTransport transport = JLineTuiTransport.open(
                 state,
                 core,
                 events,
                 terminal,
                 sessionManager,
                 resourceRuntime,
                 compactionRuntime
             )) {
            transport.runUntilExit();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to close TUI transport", exception);
        }
    }
}
