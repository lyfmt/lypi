package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.skill.SkillDescriptor;
import cn.lypi.contracts.skill.SkillSource;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class SkillMentionParserTest {
    private final SkillMentionParser parser = new SkillMentionParser(List.of(
        skill("doc", "Document workflow", "/tmp/doc/SKILL.md"),
        skill("doc-test", "Test docs", "/tmp/doc-test/SKILL.md")
    ));

    @Test
    void tokenAtLineStartIsRecognized() {
        SkillMentionToken token = parser.activeToken("$doc", 4).orElseThrow();
        assertEquals("doc", token.prefix());
        assertEquals(0, token.start());
    }

    @Test
    void tokenAfterWhitespaceIsRecognized() {
        assertEquals("doc", parser.activeToken("use $doc", 8).orElseThrow().prefix());
    }

    @Test
    void embeddedDollarIsIgnored() {
        assertTrue(parser.activeToken("hello$doc", 9).isEmpty());
    }

    @Test
    void environmentVariableIsIgnored() {
        assertTrue(parser.activeToken("$PATH", 5).isEmpty());
    }

    @Test
    void hyphenatedSkillNameUsesWholeToken() {
        assertEquals("doc-test", parser.activeToken("$doc-test", 9).orElseThrow().prefix());
    }

    @Test
    void cancelledTokenSuppressesExplicitInjectionForThatRange() {
        SkillMentionToken token = parser.activeToken("$doc", 4).orElseThrow();
        SkillMentionSuppressions suppressions = new SkillMentionSuppressions();
        suppressions.suppress(token);

        assertTrue(parser.explicitMentions("$doc", List.of(), suppressions).isEmpty());
    }

    private static SkillDescriptor skill(String name, String description, String file) {
        return new SkillDescriptor(name, description, SkillSource.PROJECT, Path.of(file), List.of(), List.of(), "sha256:" + name);
    }
}
