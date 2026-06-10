package cn.lypi.transport.tui;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class SlashCommandPicker {
    private static final List<String> BUILT_IN_COMMANDS = List.of(
        "/model",
        "/thinking",
        "/mode",
        "/permission-mode",
        "/compact"
    );

    private final List<String> commands;
    private String filter = "";
    private String acceptedCommand = "";
    private int selectedIndex;

    SlashCommandPicker(List<String> commands) {
        this.commands = commands == null ? List.of() : List.copyOf(commands);
    }

    static SlashCommandPicker withTemplates(List<String> templateNames) {
        List<String> commands = new ArrayList<>(BUILT_IN_COMMANDS);
        if (templateNames != null) {
            templateNames.stream()
                .filter(name -> name != null && !name.isBlank())
                .map(name -> name.startsWith("/") ? name : "/" + name)
                .forEach(commands::add);
        }
        return new SlashCommandPicker(commands);
    }

    void updateFilter(String filter) {
        this.filter = filter == null ? "" : filter;
        selectedIndex = Math.min(selectedIndex, Math.max(0, visibleCommands().size() - 1));
    }

    String title() {
        int size = visibleCommands().size();
        return size == 0 ? "(0/0)" : "(" + (selectedIndex + 1) + "/" + size + ")";
    }

    List<String> visibleCommands() {
        String normalized = normalize(filter);
        if (normalized.isBlank()) {
            return commands;
        }
        List<String> prefixMatches = commands.stream()
            .filter(command -> normalize(command).startsWith(normalized))
            .toList();
        if (!prefixMatches.isEmpty()) {
            return prefixMatches;
        }
        return commands.stream()
            .filter(command -> fuzzyMatches(normalize(command), normalized))
            .toList();
    }

    Optional<String> accept() {
        List<String> visible = visibleCommands();
        if (visible.isEmpty()) {
            return Optional.empty();
        }
        selectedIndex = Math.max(0, Math.min(selectedIndex, visible.size() - 1));
        acceptedCommand = visible.get(selectedIndex);
        return Optional.of(acceptedCommand);
    }

    int selectedIndex() {
        return selectedIndex;
    }

    void moveDown() {
        List<String> visible = visibleCommands();
        if (!visible.isEmpty()) {
            selectedIndex = Math.floorMod(selectedIndex + 1, visible.size());
        }
    }

    void moveUp() {
        List<String> visible = visibleCommands();
        if (!visible.isEmpty()) {
            selectedIndex = Math.floorMod(selectedIndex - 1, visible.size());
        }
    }

    List<String> secondaryOptions(List<String> options) {
        if (!"/model".equals(acceptedCommand) && !"/thinking".equals(acceptedCommand)) {
            return List.of();
        }
        String normalized = normalize(filter);
        return options.stream()
            .filter(option -> fuzzyMatches(normalize(option), normalized))
            .toList();
    }

    private boolean fuzzyMatches(String value, String query) {
        if (query.isBlank()) {
            return true;
        }
        int valueIndex = 0;
        for (int i = 0; i < query.length(); i++) {
            char expected = query.charAt(i);
            valueIndex = value.indexOf(expected, valueIndex);
            if (valueIndex < 0) {
                return false;
            }
            valueIndex++;
        }
        return true;
    }

    private String normalize(String value) {
        return (value == null ? "" : value).replace("/", "").trim().toLowerCase();
    }
}
