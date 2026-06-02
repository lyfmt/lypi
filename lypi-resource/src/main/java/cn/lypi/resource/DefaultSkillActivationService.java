package cn.lypi.resource;

import cn.lypi.contracts.resource.ResourceDiagnostic;
import cn.lypi.contracts.skill.SkillActivation;
import cn.lypi.contracts.skill.SkillDescriptor;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class DefaultSkillActivationService implements SkillActivationService {
    private final FrontmatterParser frontmatterParser = new FrontmatterParser();

    @Override
    public SkillActivationResult activate(SkillDescriptor descriptor, String activatedReason) {
        List<ResourceDiagnostic> diagnostics = new ArrayList<>();
        String body = "";
        String contentHash = descriptor.contentHash();

        var content = ResourceFiles.readString(descriptor.skillFile(), diagnostics);
        if (content.isPresent()) {
            contentHash = Hashing.sha256(content.get());
            try {
                body = frontmatterParser.parse(content.get()).body();
            } catch (IOException exception) {
                diagnostics.add(ResourceDiagnostics.warning(
                    "Failed to parse activated skill frontmatter: " + exception.getMessage(),
                    descriptor.skillFile()
                ));
            }
        }

        SkillActivation activation = new SkillActivation(
            descriptor.name(),
            descriptor.source(),
            contentHash,
            activatedReason,
            descriptor.allowedTools(),
            Instant.now()
        );
        return new SkillActivationResult(activation, body, List.copyOf(diagnostics));
    }
}
