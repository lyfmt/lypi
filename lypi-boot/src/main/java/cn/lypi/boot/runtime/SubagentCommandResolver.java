package cn.lypi.boot.runtime;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

final class SubagentCommandResolver {
    private final LyPiSubagentProperties properties;
    private final Supplier<URI> codeSourceLocation;
    private final Supplier<String> javaCommand;

    SubagentCommandResolver(LyPiSubagentProperties properties) {
        this(properties, SubagentCommandResolver::defaultCodeSourceLocation, () -> System.getProperty("sun.java.command", ""));
    }

    SubagentCommandResolver(LyPiSubagentProperties properties, Supplier<URI> codeSourceLocation) {
        this(properties, codeSourceLocation, () -> "");
    }

    SubagentCommandResolver(
        LyPiSubagentProperties properties,
        Supplier<URI> codeSourceLocation,
        Supplier<String> javaCommand
    ) {
        this.properties = properties;
        this.codeSourceLocation = codeSourceLocation;
        this.javaCommand = javaCommand;
    }

    List<String> resolve() {
        List<String> configured = properties == null ? List.of() : properties.getCommand();
        if (configured != null && !configured.isEmpty()) {
            return List.copyOf(configured);
        }
        return inferPackagedJarCommand();
    }

    private List<String> inferPackagedJarCommand() {
        List<String> command = commandFromLocation(codeSourceLocation.get());
        if (!command.isEmpty()) {
            return command;
        }
        return commandFromJavaCommand(javaCommand.get());
    }

    private List<String> commandFromLocation(URI location) {
        if (location == null || !"file".equalsIgnoreCase(location.getScheme())) {
            return List.of();
        }
        try {
            return commandFromJarPath(Path.of(location));
        } catch (IllegalArgumentException e) {
            return List.of();
        }
    }

    private List<String> commandFromJavaCommand(String command) {
        List<String> tokens = shellLikeSplit(command == null ? "" : command);
        if (tokens.isEmpty()) {
            return List.of();
        }
        return commandFromJarPath(Path.of(tokens.getFirst()));
    }

    private List<String> commandFromJarPath(Path candidate) {
        Path jarPath = candidate.toAbsolutePath().normalize();
        Path fileName = jarPath.getFileName();
        if (fileName == null || !fileName.toString().endsWith(".jar")) {
            return List.of();
        }
        return List.of("java", "-jar", jarPath.toString(), "headless-subagent");
    }

    private List<String> shellLikeSplit(String value) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (ch == '\'' && !doubleQuoted) {
                singleQuoted = !singleQuoted;
                continue;
            }
            if (ch == '"' && !singleQuoted) {
                doubleQuoted = !doubleQuoted;
                continue;
            }
            if (Character.isWhitespace(ch) && !singleQuoted && !doubleQuoted) {
                if (!current.isEmpty()) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(ch);
        }
        if (!current.isEmpty()) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    private static URI defaultCodeSourceLocation() {
        CodeSource codeSource = LyPiRuntimeAutoConfiguration.class.getProtectionDomain().getCodeSource();
        if (codeSource == null || codeSource.getLocation() == null) {
            return null;
        }
        try {
            return codeSource.getLocation().toURI();
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
