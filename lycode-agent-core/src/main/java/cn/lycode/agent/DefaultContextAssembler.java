package cn.lycode.agent;

import cn.lycode.contracts.context.ContextBudget;
import cn.lycode.contracts.context.ContextSnapshot;
import cn.lycode.contracts.prompt.SystemPrompt;
import cn.lycode.contracts.resource.ResourceSnapshot;
import cn.lycode.contracts.runtime.ResourceRuntimePort;
import cn.lycode.contracts.runtime.SessionManagerPort;
import cn.lycode.contracts.session.SessionHandle;
import cn.lycode.contracts.session.SessionContext;
import cn.lycode.contracts.skill.SkillMention;
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
        SessionContext sessionContext = sessionManager.context(leafId);
        SystemPrompt systemPrompt = request.includeSystemPrompt() ? withSkillInjections(
            resourceRuntime.buildSystemPrompt(resources, sessionContext.permissionRuntimeState()),
            request.skillMentions()
        ) : null;
        ContextBudget budget = budgetEstimator.estimate(systemPrompt, sessionContext.messages(), sessionContext.model());
        ContextSnapshot snapshot = new ContextSnapshot(
            systemPrompt,
            sessionContext.messages(),
            sessionContext.model(),
            sessionContext.thinkingLevel(),
            sessionContext.mode(),
            sessionContext.permissionRuntimeState(),
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
