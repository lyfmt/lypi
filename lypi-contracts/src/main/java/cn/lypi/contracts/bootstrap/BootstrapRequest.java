package cn.lypi.contracts.bootstrap;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public record BootstrapRequest(
    Path cwd,
    List<String> cliArgs,
    Optional<String> sessionId,
    Optional<String> initialPrompt
) {}

