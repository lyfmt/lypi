package cn.lypi.transport.tui;

import java.util.HashSet;
import java.util.Set;

final class SkillMentionSuppressions {
    private final Set<String> suppressed = new HashSet<>();

    void suppress(SkillMentionToken token) {
        if (token == null) {
            return;
        }
        suppressed.add(key(token.start(), token.prefix()));
    }

    boolean suppressed(SkillMentionToken token) {
        return token != null && suppressed.contains(key(token.start(), token.prefix()));
    }

    void clear() {
        suppressed.clear();
    }

    private String key(int start, String prefix) {
        return start + ":" + (prefix == null ? "" : prefix);
    }
}
