package cn.lypi.contracts.common;

import java.util.Map;

public record ToolProgress(
    ToolProgressKind kind,
    String title,
    String detail,
    String phase,
    String stream,
    String delta,
    Long current,
    Long total,
    Double percent,
    Map<String, Object> metadata
) {
    public ToolProgress {
        kind = kind == null ? ToolProgressKind.STATUS : kind;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        if (percent != null) {
            percent = Math.max(0.0, Math.min(100.0, percent));
        }
    }

    public static ToolProgress phase(String phase, String title) {
        return new ToolProgress(ToolProgressKind.PHASE, title, null, phase, null, null, null, null, null, Map.of());
    }

    public static ToolProgress output(String stream, String delta) {
        return new ToolProgress(ToolProgressKind.OUTPUT, null, null, null, stream, delta, null, null, null, Map.of());
    }

    public static ToolProgress counter(String title, long current, long total) {
        return new ToolProgress(
            ToolProgressKind.COUNTER,
            title,
            null,
            null,
            null,
            null,
            current,
            total,
            null,
            Map.of()
        );
    }

    public static ToolProgress percent(String title, double percent) {
        return new ToolProgress(ToolProgressKind.PERCENT, title, null, null, null, null, null, null, percent, Map.of());
    }

    public static ToolProgress status(String title, String detail) {
        return new ToolProgress(ToolProgressKind.STATUS, title, detail, null, null, null, null, null, null, Map.of());
    }

    public static ToolProgress custom(String title, Map<String, Object> metadata) {
        return new ToolProgress(ToolProgressKind.CUSTOM, title, null, null, null, null, null, null, null, metadata);
    }
}
