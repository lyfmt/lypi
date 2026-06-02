package cn.lypi.resource;

import cn.lypi.contracts.resource.ResourceDiagnostic;
import cn.lypi.contracts.skill.SkillDescriptor;
import cn.lypi.contracts.skill.SkillIndex;
import cn.lypi.contracts.skill.SkillSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

class SkillScanner {
    private final FrontmatterParser frontmatterParser = new FrontmatterParser();

    SkillIndex scan(List<ResourceLocation> locations, List<ResourceDiagnostic> diagnostics) {
        List<ResourceDiagnostic> skillDiagnostics = new ArrayList<>();
        List<SkillDescriptor> skills = new ArrayList<>();
        Set<Path> seenRealPaths = new HashSet<>();
        for (ResourceLocation location : orderedLocations(locations)) {
            for (Path root : skillRoots(location)) {
                scanSkillRoot(location, root, diagnostics, skillDiagnostics, skills, seenRealPaths);
            }
        }
        reportDuplicateNames(skills, diagnostics, skillDiagnostics);
        return new SkillIndex(List.copyOf(skills), List.copyOf(skillDiagnostics));
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

    private List<Path> skillRoots(ResourceLocation location) {
        return List.of(location.root().resolve("skills"), location.root().resolve(".ly-pi").resolve("skills"));
    }

    private void scanSkillRoot(
        ResourceLocation location,
        Path root,
        List<ResourceDiagnostic> diagnostics,
        List<ResourceDiagnostic> skillDiagnostics,
        List<SkillDescriptor> skills,
        Set<Path> seenRealPaths
    ) {
        if (!Files.isDirectory(root)) {
            return;
        }
        for (Path file : ResourceFiles.regularFiles(root, diagnostics).stream()
            .filter(path -> path.getFileName().toString().equals("SKILL.md"))
            .toList()) {
            Path realPath = realPath(file, diagnostics);
            if (realPath != null && !seenRealPaths.add(realPath)) {
                diagnostics.add(ResourceDiagnostics.warning("duplicate skill file: " + realPath, file));
                continue;
            }
            ResourceFiles.readString(file, diagnostics).ifPresent(content ->
                parseSkill(location, file, content, diagnostics, skillDiagnostics, skills)
            );
        }
    }

    private void parseSkill(
        ResourceLocation location,
        Path file,
        String content,
        List<ResourceDiagnostic> diagnostics,
        List<ResourceDiagnostic> skillDiagnostics,
        List<SkillDescriptor> skills
    ) {
        try {
            FrontmatterDocument document = frontmatterParser.parse(content);
            String name = ResourceMetadata.stringValue(document.metadata(), "name")
                .orElseGet(() -> file.getParent().getFileName().toString());
            String description = ResourceMetadata.stringValue(document.metadata(), "description").orElse("");
            skills.add(new SkillDescriptor(
                name,
                description,
                skillSource(location.layer()),
                file,
                ResourceMetadata.stringList(document.metadata().get("paths")),
                ResourceMetadata.stringList(document.metadata().get("allowed_tools")),
                Hashing.sha256(content)
            ));
        } catch (IOException exception) {
            ResourceDiagnostic diagnostic = ResourceDiagnostics.warning(
                "Failed to parse skill frontmatter: " + exception.getMessage(),
                file
            );
            diagnostics.add(diagnostic);
            skillDiagnostics.add(diagnostic);
        }
    }

    private void reportDuplicateNames(
        List<SkillDescriptor> skills,
        List<ResourceDiagnostic> diagnostics,
        List<ResourceDiagnostic> skillDiagnostics
    ) {
        Map<String, List<SkillDescriptor>> byName = new HashMap<>();
        for (SkillDescriptor skill : skills) {
            byName.computeIfAbsent(skill.name(), ignored -> new ArrayList<>()).add(skill);
        }
        byName.forEach((name, candidates) -> {
            if (candidates.size() > 1) {
                ResourceDiagnostic diagnostic = ResourceDiagnostics.warning("duplicate skill name: " + name, candidates.getLast().skillFile());
                diagnostics.add(diagnostic);
                skillDiagnostics.add(diagnostic);
            }
        });
    }

    private SkillSource skillSource(ResourceLayer layer) {
        return switch (layer) {
            case PLATFORM -> SkillSource.PLATFORM;
            case USER -> SkillSource.USER;
            case PROJECT -> SkillSource.PROJECT;
            case NESTED_PROJECT -> SkillSource.NESTED_PROJECT;
            case EXPLICIT_PATH -> SkillSource.EXPLICIT_PATH;
            case MCP_DERIVED -> SkillSource.MCP_DERIVED;
            case SESSION -> SkillSource.PROJECT;
        };
    }

    private Path realPath(Path file, List<ResourceDiagnostic> diagnostics) {
        try {
            return file.toRealPath();
        } catch (Exception exception) {
            diagnostics.add(ResourceDiagnostics.warning("Failed to resolve skill realpath: " + exception.getMessage(), file));
            return null;
        }
    }
}
