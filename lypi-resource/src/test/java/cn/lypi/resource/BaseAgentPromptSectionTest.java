package cn.lypi.resource;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class BaseAgentPromptSectionTest {
    @Test
    void appendsCodexStyleAgentContractWithoutPlatformLeakage() {
        StringBuilder content = new StringBuilder();
        var sourceNames = new ArrayList<String>();

        new BaseAgentPromptSection().appendTo(content, sourceNames);

        assertThat(content.toString()).contains(
            "You are ly-pi, a local coding agent running on the user's computer.",
            "## General",
            "## Editing Constraints",
            "Treat dirty worktrees as shared state",
            "Never use destructive commands",
            "## Workflow",
            "Do not claim success without verification evidence",
            "## Final Response"
        );
        assertThat(content.toString()).doesNotContain("Codex CLI", "apply_patch", "frontend", "rg", "ripgrep");
        assertThat(sourceNames).containsExactly("base-agent-instructions");
    }
}
