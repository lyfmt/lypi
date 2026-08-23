package cn.lycode.transport.tui;

record SkillMentionToken(int start, int end, String prefix) {
    SkillMentionToken {
        prefix = prefix == null ? "" : prefix;
    }
}
