package cn.lypi.transport.tui;

import cn.lypi.contracts.event.EventBus;
import cn.lypi.contracts.runtime.AgentCorePort;
import cn.lypi.contracts.tui.SessionRuntimeState;
import cn.lypi.contracts.tui.SlashCommand;
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
        List<SlashCommand> slashCommands
    ) throws IOException;
}
