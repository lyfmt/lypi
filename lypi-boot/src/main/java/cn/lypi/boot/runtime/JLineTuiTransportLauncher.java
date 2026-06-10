package cn.lypi.boot.runtime;

import cn.lypi.contracts.event.EventBus;
import cn.lypi.contracts.runtime.AgentCorePort;
import cn.lypi.contracts.tui.DiffViewProvider;
import cn.lypi.contracts.tui.SessionRuntimeState;
import cn.lypi.contracts.tui.SlashCommand;
import cn.lypi.transport.tui.JLineTuiTransportFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

final class JLineTuiTransportLauncher implements TransportLauncher {
    private final JLineTuiTransportFactory factory;
    private final DiffViewProvider diffViewProvider;
    private final List<SlashCommand> slashCommands;

    JLineTuiTransportLauncher(
        JLineTuiTransportFactory factory,
        DiffViewProvider diffViewProvider,
        List<SlashCommand> slashCommands
    ) {
        this.factory = factory;
        this.diffViewProvider = diffViewProvider;
        this.slashCommands = slashCommands == null ? List.of() : List.copyOf(slashCommands);
    }

    @Override
    public String name() {
        return "tui";
    }

    @Override
    public void launch(SessionRuntimeState state, AgentCorePort core, EventBus events) {
        try (Terminal terminal = TerminalBuilder.builder().system(true).build();
             var transport = factory.open(state, core, events, terminal, diffViewProvider, slashCommands)) {
            transport.runUntilExit();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to close TUI transport", exception);
        }
    }
}
