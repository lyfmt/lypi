package cn.lypi.contracts.bootstrap;

import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.prompt.SystemPrompt;
import cn.lypi.contracts.resource.ResourceSnapshot;
import cn.lypi.contracts.session.SessionHandle;
import cn.lypi.contracts.tool.ToolRegistrySnapshot;
import java.nio.file.Path;

public record BootstrapContext(
    Path cwd,
    Path projectRoot,
    SettingsBundle settings,
    ResourceSnapshot resources,
    ToolRegistrySnapshot toolRegistry,
    SessionHandle session,
    ModelSelection modelSelection,
    SystemPrompt systemPrompt
) {}

