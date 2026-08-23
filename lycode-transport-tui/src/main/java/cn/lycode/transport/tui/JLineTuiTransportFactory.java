package cn.lycode.transport.tui;

import cn.lycode.contracts.event.EventBus;
import cn.lycode.contracts.runtime.AgentCorePort;
import cn.lycode.contracts.tui.DiffViewProvider;
import cn.lycode.contracts.tui.NewSessionController;
import cn.lycode.contracts.tui.ResumeSessionController;
import cn.lycode.contracts.tui.SessionRuntimeState;
import cn.lycode.contracts.tui.SlashCommand;
import java.io.IOException;
import java.util.List;
import org.jline.terminal.Terminal;

@FunctionalInterface
public interface JLineTuiTransportFactory {
    /**
     * 打开真实 JLine TUI transport。
     */
    JLineTuiTransport open(
        SessionRuntimeState state,
        AgentCorePort core,
        EventBus events,
        Terminal terminal,
        DiffViewProvider diffViewProvider,
        ResumeSessionController resumeController,
        NewSessionController newSessionController,
        List<SlashCommand> slashCommands
    ) throws IOException;
}
