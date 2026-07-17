package cn.lypi.transport.tui;

record ToolDisplayBudget(int totalLines, int detailLines) {
    ToolDisplayBudget {
        if (totalLines < 1) {
            throw new IllegalArgumentException("totalLines must be positive");
        }
        if (detailLines < 0 || detailLines >= totalLines) {
            throw new IllegalArgumentException("detailLines must fit below the title line");
        }
    }

    static ToolDisplayBudget collapsed() {
        return new ToolDisplayBudget(5, 4);
    }

    static ToolDisplayBudget expanded(int transcriptHeight) {
        int total = Math.max(1, Math.min(40, transcriptHeight));
        return new ToolDisplayBudget(total, total - 1);
    }
}
