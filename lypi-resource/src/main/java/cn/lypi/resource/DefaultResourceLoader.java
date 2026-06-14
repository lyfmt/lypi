package cn.lypi.resource;

import cn.lypi.contracts.mcp.McpServerConfig;
import cn.lypi.contracts.prompt.PromptTemplate;
import cn.lypi.contracts.resource.ContextFile;
import cn.lypi.contracts.resource.MemorySource;
import cn.lypi.contracts.resource.ResourceDiagnostic;
import cn.lypi.contracts.resource.ResourceSnapshot;
import cn.lypi.contracts.skill.SkillIndex;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 默认资源加载器。
 *
 * NOTE: 该类只编排资源发现和解析，最终输出 ResourceSnapshot，不构建 system prompt。
 */
public class DefaultResourceLoader implements ResourceLoader {
    private final ProjectRootResolver projectRootResolver;
    private final ResourceLocationResolver locationResolver;
    private final ContextFileScanner contextFileScanner;
    private final MemorySourceScanner memorySourceScanner;
    private final SkillScanner skillScanner;
    private final PromptTemplateScanner promptTemplateScanner;
    private final McpConfigScanner mcpConfigScanner;

    public DefaultResourceLoader() {
        this(new ResourceLocationResolver());
    }

    public DefaultResourceLoader(List<Path> userRoots, List<Path> explicitRoots) {
        this(new ResourceLocationResolver(userRoots, explicitRoots));
    }

    DefaultResourceLoader(ResourceLocationResolver locationResolver) {
        this(
            new ProjectRootResolver(),
            locationResolver,
            new ContextFileScanner(),
            new MemorySourceScanner(),
            new SkillScanner(),
            new PromptTemplateScanner(),
            new McpConfigScanner()
        );
    }

    DefaultResourceLoader(
        ProjectRootResolver projectRootResolver,
        ResourceLocationResolver locationResolver,
        ContextFileScanner contextFileScanner,
        MemorySourceScanner memorySourceScanner,
        SkillScanner skillScanner,
        PromptTemplateScanner promptTemplateScanner,
        McpConfigScanner mcpConfigScanner
    ) {
        this.projectRootResolver = projectRootResolver;
        this.locationResolver = locationResolver;
        this.contextFileScanner = contextFileScanner;
        this.memorySourceScanner = memorySourceScanner;
        this.skillScanner = skillScanner;
        this.promptTemplateScanner = promptTemplateScanner;
        this.mcpConfigScanner = mcpConfigScanner;
    }

    /**
     * 发现并解析当前目录可见的资源。
     */
    @Override
    public ResourceSnapshot load(Path cwd) {
        ResourceDiscoveryPlan rootPlan = projectRootResolver.resolve(cwd);
        ResourceDiscoveryPlan discoveryPlan = locationResolver.resolve(rootPlan.projectRoot(), rootPlan.cwd());
        List<ResourceDiagnostic> diagnostics = new ArrayList<>();
        diagnostics.addAll(rootPlan.diagnostics());
        diagnostics.addAll(discoveryPlan.diagnostics());

        List<ContextFile> agentFiles = contextFileScanner.scan(discoveryPlan.locations(), diagnostics);
        List<MemorySource> memorySources = memorySourceScanner.scan(discoveryPlan.locations(), diagnostics);
        SkillIndex skillIndex = skillScanner.scan(discoveryPlan.locations(), diagnostics);
        List<PromptTemplate> promptTemplates = promptTemplateScanner.scan(discoveryPlan.locations(), diagnostics);
        List<McpServerConfig> mcpServers = mcpConfigScanner.scan(discoveryPlan.locations(), diagnostics);

        return new ResourceSnapshot(
            agentFiles,
            memorySources,
            skillIndex,
            promptTemplates,
            mcpServers,
            List.copyOf(diagnostics)
        );
    }
}
