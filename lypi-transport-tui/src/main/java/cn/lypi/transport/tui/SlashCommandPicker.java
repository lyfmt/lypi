package cn.lypi.transport.tui;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class SlashCommandPicker {
    private final List<String> commands;
    private String filter = "";
    private String acceptedCommand = "";

    SlashCommandPicker(List<String> commands) {
        this.commands = commands == null ? List.of() : List.copyOf(commands);
    }

    void updateFilter(String filter) {
        this.filter = filter == null ? "" : filter;
    }

    String title() {
        int size = visibleCommands().size();
        return size == 0 ? "(0/0)" : "(1/" + size + ")";
    }

    List<String> visibleCommands() {
        String normalized = normalize(filter);
        return commands.stream()
            .filter(command -> fuzzyMatches(normalize(command), normalized))
            .toList();
    }

    Optional<String> accept() {
        List<String> visible = visibleCommands();
        if (visible.isEmpty()) {
            return Optional.empty();
        }
        acceptedCommand = visible.getFirst();
        return Optional.of(acceptedCommand);
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
