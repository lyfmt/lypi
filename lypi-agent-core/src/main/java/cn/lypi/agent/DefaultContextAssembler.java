package cn.lypi.agent;

import cn.lypi.contracts.context.ContextBudget;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.prompt.SystemPrompt;
import cn.lypi.contracts.resource.ResourceSnapshot;
import cn.lypi.contracts.runtime.ResourceRuntimePort;
import cn.lypi.contracts.runtime.SessionManagerPort;
import cn.lypi.contracts.session.SessionHandle;
import cn.lypi.contracts.session.SessionContext;
import cn.lypi.contracts.skill.SkillMention;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.security.MessageDigest;
import java.util.List;

public final class DefaultContextAssembler implements ContextAssembler {
    private final SessionManagerPort sessionManager;
    private final ResourceRuntimePort resourceRuntime;
    private final ContextBudgetEstimator budgetEstimator;

    public DefaultContextAssembler(
        SessionManagerPort sessionManager,
        ResourceRuntimePort resourceRuntime,
        ContextBudgetEstimator budgetEstimator
    ) {
        this.sessionManager = sessionManager;
        this.resourceRuntime = resourceRuntime;
        this.budgetEstimator = budgetEstimator;
    }

    @Override
    public ContextAssembly build(ContextBuildRequest request) {
        SessionHandle handle = sessionManager.openOrCreate(request.sessionId());
        String leafId = request.leafEntryId().orElse(handle.leafId());
        ResourceSnapshot resources = resourceRuntime.load(request.cwd());
        SystemPrompt systemPrompt = request.includeSystemPrompt() ? withSkillInjections(
            resourceRuntime.buildSystemPrompt(resources),
            request.skillMentions()
        ) : null;
        SessionContext sessionContext = sessionManager.context(leafId);
        ContextBudget budget = budgetEstimator.estimate(systemPrompt, sessionContext.messages(), sessionContext.model());
        ContextSnapshot snapshot = new ContextSnapshot(
            systemPrompt,
            sessionContext.messages(),
            sessionContext.model(),
            sessionContext.thinkingLevel(),
            sessionContext.mode(),
            sessionContext.permissionMode(),
            budget
        );

        return new ContextAssembly(
            snapshot,
            resources,
            sessionContext.branchEntryIds(),
            sessionContext.appliedCompactionEntryIds(),
            List.of(),
            budget.estimatedContextTokens() > budget.autoCompactThreshold()
        );
    }

    private SystemPrompt withSkillInjections(SystemPrompt systemPrompt, List<SkillMention> skillMentions) {
        if (systemPrompt == null || skillMentions == null || skillMentions.isEmpty()) {
            return systemPrompt;
        }
        StringBuilder content = new StringBuilder(systemPrompt.content() == null ? "" : systemPrompt.content());
        List<String> sourceNames = new ArrayList<>(systemPrompt.sourceNames() == null ? List.of() : systemPrompt.sourceNames());
        for (SkillMention mention : skillMentions) {
            sourceNames.add("skill:" + mention.name());
            content.append("\n\n").append(skillFragment(mention));
        }
        String nextContent = content.toString();
        return new SystemPrompt(nextContent, List.copyOf(sourceNames), sha256(nextContent));
    }

    private String skillFragment(SkillMention mention) {
        StringBuilder fragment = new StringBuilder();
        fragment.append("<skill>\n");
        fragment.append("<name>").append(mention.name()).append("</name>\n");
        fragment.append("<path>").append(mention.skillFile()).append("</path>\n");
        try {
            fragment.append(Files.readString(mention.skillFile()).strip()).append('\n');
        } catch (Exception exception) {
            fragment.append("<warning>Failed to read skill file: ")
                .append(exception.getMessage())
                .append("</warning>\n");
        }
        fragment.append("</skill>");
        return fragment.toString();
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder("sha256:");
            for (byte part : hash) {
                result.append(String.format("%02x", part));
            }
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to hash system prompt", exception);
        }
    }
}
