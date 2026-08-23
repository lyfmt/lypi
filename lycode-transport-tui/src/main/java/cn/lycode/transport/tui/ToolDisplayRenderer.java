package cn.lycode.transport.tui;

import cn.lycode.contracts.tui.TuiToolBlock;

interface ToolDisplayRenderer {
    ToolDisplayModel render(TuiToolBlock block, boolean expanded, ToolDisplayBudget budget);
}
