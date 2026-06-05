package cn.lypi.contracts.session;

import java.time.Instant;
import java.util.Map;

public record CommandEntry(
    String id,
    String parentId,
    CommandKind kind,
    String rawCommand,
    String commandName,
    Map<String, Object> arguments,
    Instant timestamp
) implements SessionEntry {
    public CommandEntry {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
}
