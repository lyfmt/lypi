package cn.lypi.resource;

import cn.lypi.contracts.prompt.PromptTemplate;
import cn.lypi.contracts.prompt.PromptTemplateSource;
import cn.lypi.contracts.resource.ResourceDiagnostic;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 扫描 Prompt Template 定义。
 *
 * NOTE: 同名模板按资源优先级覆盖，并通过 diagnostics 暴露覆盖关系。
 */
class PromptTemplateScanner {
    private final FrontmatterParser frontmatterParser = new FrontmatterParser();

    /**
     * 从资源位置中扫描 Markdown Prompt Template。
     */
    List<PromptTemplate> scan(List<ResourceLocation> locations, List<ResourceDiagnostic> diagnostics) {
        Map<String, PrioritizedResource<PromptTemplate>> selected = new LinkedHashMap<>();
        for (ResourceLocation location : orderedLocations(locations)) {
            for (Path root : promptRoots(location)) {
                scanPromptRoot(location, root, diagnostics, selected);
            }
        }
        return selected.values().stream().map(PrioritizedResource::value).toList();
    }

    private List<ResourceLocation> orderedLocations(List<ResourceLocation> locations) {
        return locations.stream()
            .filter(location -> switch (location.layer()) {
                case PLATFORM, USER, PROJECT, NESTED_PROJECT, EXPLICIT_PATH -> true;
                case SESSION, MCP_DERIVED -> false;
            })
            .sorted(Comparator.comparingInt(ResourceLocation::priority))
            .toList();
    }

    private List<Path> promptRoots(ResourceLocation location) {
        return List.of(location.root().resolve("prompts"), location.root().resolve(".ly-pi").resolve("prompts"));
    }

    private void scanPromptRoot(
        ResourceLocation location,
        Path root,
        List<ResourceDiagnostic> diagnostics,
        Map<String, PrioritizedResource<PromptTemplate>> selected
    ) {
        if (!Files.isDirectory(root)) {
            return;
        }
        for (Path file : ResourceFiles.regularFiles(root, diagnostics).stream()
            .filter(path -> path.getFileName().toString().endsWith(".md"))
            .toList()) {
            ResourceFiles.readString(file, diagnostics).ifPresent(content ->
                parsePrompt(location, file, content, diagnostics, selected)
            );
        }
    }

    private void parsePrompt(
        ResourceLocation location,
        Path file,
        String content,
        List<ResourceDiagnostic> diagnostics,
        Map<String, PrioritizedResource<PromptTemplate>> selected
    ) {
        try {
            FrontmatterDocument document = frontmatterParser.parse(content);
            String name = ResourceMetadata.stringValue(document.metadata(), "name")
                .orElseGet(() -> stripExtension(file.getFileName().toString()));
            String description = ResourceMetadata.stringValue(document.metadata(), "description").orElse("");
            PromptTemplate template = new PromptTemplate(
                name,
                description,
                promptSource(location.layer()),
                ResourceMetadata.promptParameters(document.metadata().get("parameters")),
                document.body(),
                Hashing.sha256(content)
            );
            mergeTemplate(name, template, location, file, diagnostics, selected);
        } catch (IOException exception) {
            diagnostics.add(ResourceDiagnostics.warning("Failed to parse prompt template: " + exception.getMessage(), file));
        }
    }

    private void mergeTemplate(
        String name,
        PromptTemplate template,
        ResourceLocation location,
        Path file,
        List<ResourceDiagnostic> diagnostics,
        Map<String, PrioritizedResource<PromptTemplate>> selected
    ) {
        PrioritizedResource<PromptTemplate> existing = selected.get(name);
        if (existing == null) {
            selected.put(name, new PrioritizedResource<>(template, location.priority(), location));
            return;
        }
        if (location.priority() >= existing.priority()) {
            diagnostics.add(ResourceDiagnostics.warning("template override: " + name, file));
            selected.put(name, new PrioritizedResource<>(template, location.priority(), location));
        } else {
            diagnostics.add(ResourceDiagnostics.warning("template shadowed by higher priority template: " + name, file));
        }
    }

    private PromptTemplateSource promptSource(ResourceLayer layer) {
        return switch (layer) {
            case PLATFORM -> PromptTemplateSource.PACKAGE;
            case USER -> PromptTemplateSource.USER;
            case PROJECT, NESTED_PROJECT, EXPLICIT_PATH, SESSION, MCP_DERIVED -> PromptTemplateSource.PROJECT;
        };
    }

    private String stripExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex < 0 ? fileName : fileName.substring(0, dotIndex);
    }
}
