package cn.lypi.transport.tui;

import cn.lypi.contracts.tui.SessionResumeInfo;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

final class ResumeSessionSelector {
    private final List<SessionResumeInfo> sessions;
    private final Path currentSessionPath;
    private final int maxVisible;
    private String search = "";
    private int selectedIndex;

    ResumeSessionSelector(List<SessionResumeInfo> sessions, Path currentSessionPath, int maxVisible) {
        this.sessions = sessions == null ? List.of() : List.copyOf(sessions);
        this.currentSessionPath = currentSessionPath == null ? null : currentSessionPath.toAbsolutePath().normalize();
        this.maxVisible = Math.max(1, maxVisible);
    }

    void acceptText(String text) {
        search += text == null ? "" : text;
        clampSelection();
    }

    void replaceSearch(String search) {
        this.search = search == null ? "" : search;
        selectedIndex = 0;
        clampSelection();
    }

    void moveDown() {
        List<FlatSession> visible = visibleSessions();
        if (!visible.isEmpty()) {
            selectedIndex = Math.min(visible.size() - 1, selectedIndex + 1);
        }
    }

    void moveUp() {
        if (selectedIndex > 0) {
            selectedIndex--;
        }
    }

    Optional<SessionResumeInfo> selectedSession() {
        List<FlatSession> visible = visibleSessions();
        if (visible.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(visible.get(selectedIndex).session());
    }

    List<String> visibleSessionIds() {
        return visibleSessions().stream()
            .map(flat -> flat.session().sessionId())
            .toList();
    }

    List<String> render(int width) {
        int safeWidth = Math.max(1, width);
        List<String> lines = new ArrayList<>();
        lines.add(AnsiWidth.truncate("Resume Session (Current Folder)", safeWidth));
        lines.add(AnsiWidth.truncate(search, safeWidth));
        lines.add("");
        List<FlatSession> visible = visibleSessions();
        if (visible.isEmpty()) {
            lines.add("  No sessions in current folder.");
            return lines;
        }
        int start = Math.max(0, Math.min(selectedIndex - maxVisible / 2, visible.size() - maxVisible));
        int end = Math.min(start + maxVisible, visible.size());
        for (int index = start; index < end; index++) {
            FlatSession flat = visible.get(index);
            lines.add(renderLine(flat, index == selectedIndex, safeWidth));
        }
        if (start > 0 || end < visible.size()) {
            lines.add(AnsiWidth.truncate("  (" + (selectedIndex + 1) + "/" + visible.size() + ")", safeWidth));
        }
        return lines;
    }

    private String renderLine(FlatSession flat, boolean selected, int width) {
        SessionResumeInfo session = flat.session();
        String cursor = selected ? "› " : "  ";
        String prefix = treePrefix(flat);
        String message = normalize(session.firstMessage());
        String right = session.messageCount() + " " + formatAge(session.modified());
        int available = Math.max(10, width - AnsiWidth.displayWidth(cursor) - AnsiWidth.displayWidth(prefix) - AnsiWidth.displayWidth(right) - 1);
        String left = cursor + prefix + AnsiWidth.truncate(message, available);
        int spacing = Math.max(1, width - AnsiWidth.displayWidth(left) - AnsiWidth.displayWidth(right));
        return AnsiWidth.truncate(left + " ".repeat(spacing) + right, width);
    }

    private String treePrefix(FlatSession flat) {
        if (flat.depth() == 0) {
            return "";
        }
        StringBuilder prefix = new StringBuilder();
        for (boolean continues : flat.ancestorContinues()) {
            prefix.append(continues ? "│  " : "   ");
        }
        prefix.append(flat.last() ? "└─ " : "├─ ");
        return prefix.toString();
    }

    private List<FlatSession> visibleSessions() {
        String trimmed = search.trim();
        List<SessionResumeInfo> filtered = sessions.stream()
            .filter(session -> matches(session, trimmed))
            .toList();
        if (trimmed.isBlank()) {
            return flattenThreaded(filtered);
        }
        return filtered.stream()
            .map(session -> new FlatSession(session, 0, true, List.of()))
            .toList();
    }

    private List<FlatSession> flattenThreaded(List<SessionResumeInfo> input) {
        List<SessionNode> roots = buildTree(input);
        List<FlatSession> result = new ArrayList<>();
        for (int index = 0; index < roots.size(); index++) {
            flatten(roots.get(index), 0, List.of(), index == roots.size() - 1, result);
        }
        return result;
    }

    private List<SessionNode> buildTree(List<SessionResumeInfo> input) {
        java.util.Map<Path, SessionNode> byPath = new java.util.LinkedHashMap<>();
        for (SessionResumeInfo session : input) {
            byPath.put(session.path().toAbsolutePath().normalize(), new SessionNode(session));
        }
        List<SessionNode> roots = new ArrayList<>();
        for (SessionNode node : byPath.values()) {
            Optional<Path> parent = node.session().parentSessionPath().map(path -> path.toAbsolutePath().normalize());
            if (parent.isPresent() && byPath.containsKey(parent.orElseThrow())) {
                byPath.get(parent.orElseThrow()).children().add(node);
            } else {
                roots.add(node);
            }
        }
        Comparator<SessionNode> byModified = Comparator.comparing((SessionNode node) -> node.session().modified()).reversed();
        roots.sort(byModified);
        for (SessionNode root : roots) {
            sortChildren(root, byModified);
        }
        return roots;
    }

    private void sortChildren(SessionNode node, Comparator<SessionNode> comparator) {
        node.children().sort(comparator);
        for (SessionNode child : node.children()) {
            sortChildren(child, comparator);
        }
    }

    private void flatten(SessionNode node, int depth, List<Boolean> ancestors, boolean last, List<FlatSession> result) {
        result.add(new FlatSession(node.session(), depth, last, ancestors));
        for (int index = 0; index < node.children().size(); index++) {
            boolean childLast = index == node.children().size() - 1;
            List<Boolean> nextAncestors = new ArrayList<>(ancestors);
            if (depth > 0) {
                nextAncestors.add(!last);
            }
            flatten(node.children().get(index), depth + 1, List.copyOf(nextAncestors), childLast, result);
        }
    }

    private boolean matches(SessionResumeInfo session, String query) {
        if (query.isBlank()) {
            return true;
        }
        String haystack = (session.sessionId() + " " + session.allMessagesText() + " " + session.cwd()).toLowerCase();
        if (query.startsWith("re:")) {
            try {
                return Pattern.compile(query.substring(3), Pattern.CASE_INSENSITIVE).matcher(haystack).find();
            } catch (PatternSyntaxException exception) {
                return false;
            }
        }
        List<String> tokens = parseTokens(query);
        for (String token : tokens) {
            if (!haystack.contains(token.toLowerCase())) {
                return false;
            }
        }
        return true;
    }

    private List<String> parseTokens(String query) {
        List<String> tokens = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < query.length(); index++) {
            char ch = query.charAt(index);
            if (ch == '"') {
                if (quoted) {
                    addToken(tokens, token);
                    quoted = false;
                } else {
                    addToken(tokens, token);
                    quoted = true;
                }
                continue;
            }
            if (!quoted && Character.isWhitespace(ch)) {
                addToken(tokens, token);
                continue;
            }
            token.append(ch);
        }
        addToken(tokens, token);
        return tokens;
    }

    private void addToken(List<String> tokens, StringBuilder token) {
        String value = token.toString().trim();
        token.setLength(0);
        if (!value.isBlank()) {
            tokens.add(value);
        }
    }

    private void clampSelection() {
        selectedIndex = Math.max(0, Math.min(selectedIndex, Math.max(0, visibleSessions().size() - 1)));
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("[\\x00-\\x1f\\x7f]", " ").trim();
    }

    private String formatAge(Instant modified) {
        if (modified == null) {
            return "";
        }
        long minutes = Math.max(0, Duration.between(modified, Instant.now()).toMinutes());
        if (minutes < 1) {
            return "now";
        }
        if (minutes < 60) {
            return minutes + "m";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return hours + "h";
        }
        long days = hours / 24;
        if (days < 7) {
            return days + "d";
        }
        if (days < 30) {
            return days / 7 + "w";
        }
        if (days < 365) {
            return days / 30 + "mo";
        }
        return days / 365 + "y";
    }

    private record SessionNode(SessionResumeInfo session, List<SessionNode> children) {
        private SessionNode(SessionResumeInfo session) {
            this(session, new ArrayList<>());
        }
    }

    private record FlatSession(SessionResumeInfo session, int depth, boolean last, List<Boolean> ancestorContinues) {
    }
}
