package cn.lypi.boot.runtime;

import cn.lypi.contracts.event.EventBus;
import cn.lypi.contracts.runtime.AgentCorePort;
import cn.lypi.contracts.tui.SessionRuntimeState;
import cn.lypi.transport.tui.JLineTuiTransport;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

final class JLineTuiTransportLauncher implements TransportLauncher {
    @Override
    public String name() {
        return "tui";
    }

    @Override
    public void launch(SessionRuntimeState state, AgentCorePort core, EventBus events) {
        try (Terminal terminal = TerminalBuilder.builder().system(true).build();
             JLineTuiTransport transport = JLineTuiTransport.open(state, core, events, terminal)) {
            transport.runUntilExit();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to close TUI transport", exception);
        }
    }
}
