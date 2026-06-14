package cn.lypi.transport.tui;

import cn.lypi.contracts.tui.TuiToolBlock;

interface ToolDisplayRenderer {
    ToolDisplayModel render(TuiToolBlock block, boolean expanded);

    default ToolDisplayModel render(TuiToolBlock block, boolean expanded, int detailLineLimit) {
        return render(block, expanded);
    }
}
