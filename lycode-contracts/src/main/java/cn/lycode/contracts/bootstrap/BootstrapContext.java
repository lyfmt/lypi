package cn.lycode.contracts.bootstrap;

import cn.lycode.contracts.model.ModelSelection;
import cn.lycode.contracts.prompt.SystemPrompt;
import cn.lycode.contracts.resource.ResourceSnapshot;
import cn.lycode.contracts.session.SessionHandle;
import cn.lycode.contracts.tool.ToolRegistrySnapshot;
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

