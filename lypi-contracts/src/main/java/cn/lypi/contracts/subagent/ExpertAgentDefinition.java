package cn.lypi.contracts.subagent;

import java.nio.file.Path;
import java.util.List;

public record ExpertAgentDefinition(
    String name,
    String provider,
    String model,
    String prompt,
    List<String> tools,
    Path sourceFile
) {
    public ExpertAgentDefinition {
        tools = tools == null ? List.of() : List.copyOf(tools);
        sourceFile = sourceFile == null ? null : sourceFile.toAbsolutePath().normalize();
    }
}
