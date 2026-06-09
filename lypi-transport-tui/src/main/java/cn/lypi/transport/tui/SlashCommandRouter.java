package cn.lypi.transport.tui;

import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.prompt.PromptParameter;
import cn.lypi.contracts.prompt.PromptTemplate;
import cn.lypi.contracts.resource.ResourceSnapshot;
import cn.lypi.contracts.runtime.CompactionRequest;
import cn.lypi.contracts.runtime.CompactionResult;
import cn.lypi.contracts.runtime.CompactionRuntimePort;
import cn.lypi.contracts.runtime.ResourceRuntimePort;
import cn.lypi.contracts.runtime.SessionManagerPort;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.session.ModeChangeEntry;
import cn.lypi.contracts.session.ModelChangeEntry;
import cn.lypi.contracts.session.PermissionModeChangeEntry;
import cn.lypi.contracts.session.SessionContext;
import cn.lypi.contracts.session.ThinkingChangeEntry;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SlashCommandRouter {
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{([A-Za-z0-9_.-]+)}}");
    private static final List<String> BUILT_IN_COMMANDS = List.of(
        "/compact",
        "/mode",
        "/model",
        "/permission-mode",
        "/thinking"
    );
    private final String sessionId;
    private final Path cwd;
    private final SessionManagerPort sessionManager;
    private final ResourceRuntimePort resourceRuntime;
    private final CompactionRuntimePort compactionRuntime;

    SlashCommandRouter(
        String sessionId,
        Path cwd,
        SessionManagerPort sessionManager,
        ResourceRuntimePort resourceRuntime
    ) {
        this(sessionId, cwd, sessionManager, resourceRuntime, null);
    }

    SlashCommandRouter(
        String sessionId,
        Path cwd,
        SessionManagerPort sessionManager,
        ResourceRuntimePort resourceRuntime,
        CompactionRuntimePort compactionRuntime
    ) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        this.cwd = cwd == null ? Path.of(".") : cwd;
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager must not be null");
        this.resourceRuntime = Objects.requireNonNull(resourceRuntime, "resourceRuntime must not be null");
        this.compactionRuntime = compactionRuntime;
    }

    SlashCommandResult route(String input) {
        SlashCommandArguments arguments = SlashCommandArguments.parse(input);
        String command = arguments.commandName();
        if (!command.startsWith("/") || command.length() == 1) {
            return SlashCommandResult.notMatched();
        }
        CommandMatch match = matchCommand(command);
        if (match.ambiguous()) {
            return SlashCommandResult.error("ambiguous slash command: " + command + " matches " + String.join(", ", match.matches()));
        }
        if (match.command().isEmpty()) {
            return SlashCommandResult.notMatched();
        }
        return switch (match.command().orElseThrow()) {
            case "/model" -> routeModel(arguments, input);
            case "/thinking" -> routeThinking(arguments, input);
            case "/mode" -> routeMode(arguments, input);
            case "/permission-mode" -> routePermissionMode(arguments, input);
            case "/compact" -> routeCompact(arguments);
            default -> routePromptTemplate(match.command().orElseThrow().substring(1), arguments);
        };
    }

    private CommandMatch matchCommand(String command) {
        String normalized = command.toLowerCase(Locale.ROOT);
        List<String> commands = candidateCommands();
        Optional<String> exact = commands.stream()
            .filter(candidate -> candidate.equalsIgnoreCase(normalized))
            .findFirst();
        if (exact.isPresent()) {
            return CommandMatch.single(exact.orElseThrow());
        }
        List<String> matches = commands.stream()
            .filter(candidate -> candidate.toLowerCase(Locale.ROOT).startsWith(normalized))
            .toList();
        if (matches.isEmpty()) {
            return CommandMatch.none();
        }
        if (matches.size() == 1) {
            return CommandMatch.single(matches.getFirst());
        }
        return CommandMatch.ambiguous(matches);
    }

    private List<String> candidateCommands() {
        List<String> commands = new java.util.ArrayList<>(BUILT_IN_COMMANDS);
        commands.sort(String::compareTo);
        ResourceSnapshot resources = resourceRuntime.load(cwd);
        resources.promptTemplates().stream()
            .map(PromptTemplate::name)
            .filter(name -> name != null && !name.isBlank())
            .map(name -> name.startsWith("/") ? name : "/" + name)
            .forEach(commands::add);
        return List.copyOf(commands);
    }

    private SlashCommandResult routeCompact(SlashCommandArguments arguments) {
        if (!arguments.positionals().isEmpty() || !arguments.named().isEmpty()) {
            return SlashCommandResult.error("usage: /compact");
        }
        if (compactionRuntime == null) {
            return SlashCommandResult.error("compact: compaction runtime is unavailable");
        }
        try {
            CompactionResult result = compactionRuntime.compact(new CompactionRequest(
                sessionId,
                Optional.of(currentLeafId()),
                cwd,
                () -> false
            ));
            if (result.compacted()) {
                return SlashCommandResult.notice("compact: " + result.message());
            }
            return SlashCommandResult.error("compact: " + result.message());
        } catch (RuntimeException exception) {
            return SlashCommandResult.error("compact: " + errorMessage(exception));
        }
    }

    private SlashCommandResult routePromptTemplate(String templateName, SlashCommandArguments arguments) {
        ResourceSnapshot resources = resourceRuntime.load(cwd);
        PromptTemplate template = resources.promptTemplates().stream()
            .filter(candidate -> normalizedTemplateName(candidate).equals(templateName))
            .findFirst()
            .orElse(null);
        if (template == null) {
            return SlashCommandResult.notMatched();
        }
        for (PromptParameter parameter : template.parameters()) {
            if (parameter.required()
                && !arguments.named().containsKey(parameter.name())
                && parameter.defaultValue().isEmpty()) {
                return SlashCommandResult.error("missing required parameter: " + parameter.name());
            }
        }
        return SlashCommandResult.submitPrompt(renderTemplate(template, arguments.named()));
    }

    private SlashCommandResult routeModel(SlashCommandArguments arguments, String reason) {
        if (arguments.positionals().size() != 1) {
            return SlashCommandResult.error("usage: /model <provider>/<model> or /model <model>");
        }
        String leafId = currentLeafId();
        SessionContext context = currentContext(leafId);
        String provider = context.model().provider();
        String modelId = arguments.positionals().getFirst();
        int separator = modelId.indexOf('/');
        if (separator == 0 || separator == modelId.length() - 1) {
            return SlashCommandResult.error("usage: /model <provider>/<model> or /model <model>");
        }
        if (separator > 0 && separator < modelId.length() - 1) {
            provider = modelId.substring(0, separator);
            modelId = modelId.substring(separator + 1);
        }
        append(new ModelChangeEntry(
            newEntryId(),
            leafId,
            new ModelSelection(provider, modelId, context.thinkingLevel()),
            reason,
            Instant.now()
        ));
        return SlashCommandResult.consumedCommand();
    }

    private SlashCommandResult routeThinking(SlashCommandArguments arguments, String reason) {
        if (arguments.positionals().size() != 1) {
            return SlashCommandResult.error("usage: /thinking <off|minimal|low|medium|high|xhigh|max>");
        }
        ThinkingLevel level = parseEnum(ThinkingLevel.class, arguments.positionals().getFirst());
        if (level == null) {
            return SlashCommandResult.error("unknown thinking level: " + arguments.positionals().getFirst());
        }
        append(new ThinkingChangeEntry(newEntryId(), currentLeafId(), level, reason, Instant.now()));
        return SlashCommandResult.consumedCommand();
    }

    private SlashCommandResult routeMode(SlashCommandArguments arguments, String reason) {
        if (arguments.positionals().size() != 1) {
            return SlashCommandResult.error("usage: /mode <plan|execute|bypass>");
        }
        AgentMode mode = parseEnum(AgentMode.class, arguments.positionals().getFirst());
        if (mode == null) {
            return SlashCommandResult.error("unknown agent mode: " + arguments.positionals().getFirst());
        }
        append(new ModeChangeEntry(newEntryId(), currentLeafId(), mode, reason, Instant.now()));
        return SlashCommandResult.consumedCommand();
    }

    private SlashCommandResult routePermissionMode(SlashCommandArguments arguments, String reason) {
        if (arguments.positionals().size() != 1) {
            return SlashCommandResult.error("usage: /permission-mode <plan|default-execute|accept-edits|dont-ask|bypass>");
        }
        PermissionMode mode = parseEnum(PermissionMode.class, arguments.positionals().getFirst());
        if (mode == null) {
            return SlashCommandResult.error("unknown permission mode: " + arguments.positionals().getFirst());
        }
        append(new PermissionModeChangeEntry(newEntryId(), currentLeafId(), mode, reason, Instant.now()));
        return SlashCommandResult.consumedCommand();
    }

    private SessionContext currentContext(String leafId) {
        return sessionManager.context(leafId);
    }

    private String currentLeafId() {
        return sessionManager.currentView().leafId();
    }

    private void append(cn.lypi.contracts.session.SessionEntry entry) {
        sessionManager.append(entry);
    }

    private <T extends Enum<T>> T parseEnum(Class<T> type, String value) {
        String normalized = value == null ? "" : value.trim().replace('-', '_').toUpperCase(Locale.ROOT);
        for (T candidate : type.getEnumConstants()) {
            if (candidate.name().equals(normalized)) {
                return candidate;
            }
        }
        return null;
    }

    private String newEntryId() {
        return "entry_" + UUID.randomUUID().toString().replace("-", "");
    }

    private String renderTemplate(PromptTemplate template, Map<String, String> arguments) {
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(template.templateBody());
        StringBuilder rendered = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String replacement = valueFor(template, arguments, name);
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    private String valueFor(PromptTemplate template, Map<String, String> arguments, String name) {
        if (arguments.containsKey(name)) {
            return arguments.get(name);
        }
        return template.parameters().stream()
            .filter(parameter -> parameter.name().equals(name))
            .flatMap(parameter -> parameter.defaultValue().stream())
            .findFirst()
            .orElse("{{" + name + "}}");
    }

    private String normalizedTemplateName(PromptTemplate template) {
        String name = template.name() == null ? "" : template.name();
        return name.startsWith("/") ? name.substring(1) : name;
    }

    private String errorMessage(RuntimeException exception) {
        if (exception.getMessage() != null && !exception.getMessage().isBlank()) {
            return exception.getMessage();
        }
        return exception.getClass().getSimpleName();
    }

    private record CommandMatch(Optional<String> command, List<String> matches) {
        private static CommandMatch single(String command) {
            return new CommandMatch(Optional.of(command), List.of(command));
        }

        private static CommandMatch none() {
            return new CommandMatch(Optional.empty(), List.of());
        }

        private static CommandMatch ambiguous(List<String> matches) {
            return new CommandMatch(Optional.empty(), List.copyOf(matches));
        }

        private boolean ambiguous() {
            return command.isEmpty() && matches.size() > 1;
        }
    }
}
