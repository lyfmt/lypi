package cn.lycode.boot.runtime;

import cn.lycode.contracts.event.EventBus;
import cn.lycode.contracts.runtime.AgentCorePort;
import cn.lycode.contracts.tui.DiffViewProvider;
import cn.lycode.contracts.tui.NewSessionController;
import cn.lycode.contracts.tui.ResumeSessionController;
import cn.lycode.contracts.tui.SessionRuntimeState;
import cn.lycode.contracts.tui.SlashCommand;
import cn.lycode.transport.tui.JLineTuiTransportFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

final class JLineTuiTransportLauncher implements TransportLauncher {
    private final JLineTuiTransportFactory factory;
    private final DiffViewProvider diffViewProvider;
    private final ResumeSessionController resumeController;
    private final NewSessionController newSessionController;
    private final List<SlashCommand> slashCommands;

    JLineTuiTransportLauncher(
        JLineTuiTransportFactory factory,
        DiffViewProvider diffViewProvider,
        ResumeSessionController resumeController,
        NewSessionController newSessionController,
        List<SlashCommand> slashCommands
    ) {
        this.factory = factory;
        this.diffViewProvider = diffViewProvider;
        this.resumeController = resumeController;
        this.newSessionController = newSessionController;
        this.slashCommands = slashCommands == null ? List.of() : List.copyOf(slashCommands);
    }

    @Override
    public String name() {
        return "tui";
    }

    @Override
    public void launch(SessionRuntimeState state, AgentCorePort core, EventBus events) {
        try (Terminal terminal = TerminalBuilder.builder().system(true).build();
             var transport = factory.open(
                 state,
                 core,
                 events,
                 terminal,
                 diffViewProvider,
                 resumeController,
                 newSessionController,
                 slashCommands
             )) {
            transport.runUntilExit();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to close TUI transport", exception);
        }
    }
}
