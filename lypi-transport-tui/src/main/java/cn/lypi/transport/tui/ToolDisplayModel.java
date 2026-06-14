package cn.lypi.transport.tui;

import java.util.List;

record ToolDisplayModel(String title, List<String> summaryLines, List<String> previewLines) {
    ToolDisplayModel {
        title = title == null ? "" : title;
        summaryLines = summaryLines == null ? List.of() : List.copyOf(summaryLines);
        previewLines = previewLines == null ? List.of() : List.copyOf(previewLines);
    }
}
