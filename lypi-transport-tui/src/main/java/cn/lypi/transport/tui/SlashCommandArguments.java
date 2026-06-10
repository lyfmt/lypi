package cn.lypi.transport.tui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

record SlashCommandArguments(List<String> tokens, List<String> positionals, Map<String, String> named) {
    SlashCommandArguments {
        tokens = List.copyOf(tokens == null ? List.of() : tokens);
        positionals = List.copyOf(positionals == null ? List.of() : positionals);
        named = Map.copyOf(named == null ? Map.of() : named);
    }

    static SlashCommandArguments parse(String input) {
        List<String> tokens = tokenize(input == null ? "" : input);
        List<String> positionals = new ArrayList<>();
        Map<String, String> named = new LinkedHashMap<>();
        for (int i = 1; i < tokens.size(); i++) {
            String token = tokens.get(i);
            int equalsIndex = token.indexOf('=');
            if (equalsIndex > 0) {
                named.put(token.substring(0, equalsIndex), token.substring(equalsIndex + 1));
            } else {
                positionals.add(token);
            }
        }
        return new SlashCommandArguments(tokens, positionals, named);
    }

    String commandName() {
        if (tokens.isEmpty()) {
            return "";
        }
        return tokens.getFirst();
    }

    private static List<String> tokenize(String input) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean escaping = false;
        for (int i = 0; i < input.length(); i++) {
            char value = input.charAt(i);
            if (escaping) {
                current.append(value);
                escaping = false;
                continue;
            }
            if (value == '\\') {
                escaping = true;
                continue;
            }
            if (value == '"') {
                quoted = !quoted;
                continue;
            }
            if (Character.isWhitespace(value) && !quoted) {
                addToken(tokens, current);
                continue;
            }
            current.append(value);
        }
        if (escaping) {
            current.append('\\');
        }
        addToken(tokens, current);
        return List.copyOf(tokens);
    }

    private static void addToken(List<String> tokens, StringBuilder current) {
        if (!current.isEmpty()) {
            tokens.add(current.toString());
            current.setLength(0);
        }
    }
}
