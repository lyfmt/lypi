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
import cn.lypi.contracts.tui.SlashCommand;
import java.nio.file.Path;
import java.util.LinkedHashMap;
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
    private final List<SlashCommand> slashCommands;

    SlashCommandRouter(
        String sessionId,
        Path cwd,
        SessionManagerPort sessionManager,
        ResourceRuntimePort resourceRuntime
    ) {
        this(sessionId, cwd, sessionManager, resourceRuntime, null, List.of());
    }

    SlashCommandRouter(
        String sessionId,
        Path cwd,
        SessionManagerPort sessionManager,
        ResourceRuntimePort resourceRuntime,
        CompactionRuntimePort compactionRuntime
    ) {
        this(sessionId, cwd, sessionManager, resourceRuntime, compactionRuntime, List.of());
    }

    SlashCommandRouter(
        String sessionId,
        Path cwd,
        SessionManagerPort sessionManager,
        ResourceRuntimePort resourceRuntime,
        CompactionRuntimePort compactionRuntime,
        List<SlashCommand> slashCommands
    ) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        this.cwd = cwd == null ? Path.of(".") : cwd;
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager must not be null");
        this.resourceRuntime = Objects.requireNonNull(resourceRuntime, "resourceRuntime must not be null");
        this.compactionRuntime = compactionRuntime;
        this.slashCommands = safeSlashCommands(slashCommands);
    }

    SlashCommandRouter(List<SlashCommand> slashCommands) {
        this.sessionId = "";
        this.cwd = Path.of(".");
        this.sessionManager = null;
        this.resourceRuntime = null;
        this.compactionRuntime = null;
        this.slashCommands = safeSlashCommands(slashCommands);
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
            default -> routeExternalOrPromptTemplate(match.command().orElseThrow(), arguments);
        };
    }

    List<String> commandNames() {
        return candidateCommands();
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
        List<String> commands = new java.util.ArrayList<>();
        if (sessionManager != null && resourceRuntime != null) {
            commands.addAll(BUILT_IN_COMMANDS);
        }
        slashCommands.stream()
            .map(SlashCommand::name)
            .filter(name -> name != null && !name.isBlank())
            .map(name -> name.startsWith("/") ? name : "/" + name)
            .forEach(command -> addUnique(commands, command));
        if (resourceRuntime != null) {
            ResourceSnapshot resources = resourceRuntime.load(cwd);
            resources.promptTemplates().stream()
                .map(PromptTemplate::name)
                .filter(name -> name != null && !name.isBlank())
                .map(name -> name.startsWith("/") ? name : "/" + name)
                .forEach(command -> addUnique(commands, command));
        }
        commands.sort(String::compareTo);
        return List.copyOf(commands);
    }

    private void addUnique(List<String> commands, String command) {
        if (!commands.contains(command)) {
            commands.add(command);
        }
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
        if (resourceRuntime == null) {
            return SlashCommandResult.notMatched();
        }
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

    private SlashCommandResult routeExternalOrPromptTemplate(String commandName, SlashCommandArguments arguments) {
        Optional<SlashCommand> slashCommand = externalCommand(commandName);
        if (slashCommand.isPresent()) {
            return routeExternal(slashCommand.orElseThrow(), arguments);
        }
        return routePromptTemplate(commandName.substring(1), arguments);
    }

    private SlashCommandResult routeExternal(SlashCommand command, SlashCommandArguments arguments) {
        try {
            command.handler().handle(externalArguments(command, arguments));
        } catch (RuntimeException exception) {
            return SlashCommandResult.error(command.name() + ": " + errorMessage(exception));
        }
        String output = command.handler().lastOutput();
        if (output == null || output.isBlank()) {
            return SlashCommandResult.consumedCommand();
        }
        return SlashCommandResult.notice(output);
    }

    private Optional<SlashCommand> externalCommand(String commandName) {
        String normalized = commandName == null ? "" : commandName.replaceFirst("^/", "");
        return slashCommands.stream()
            .filter(command -> command.name().equals(normalized))
            .findFirst();
    }

    private Map<String, String> externalArguments(SlashCommand command, SlashCommandArguments parsed) {
        Map<String, String> arguments = new LinkedHashMap<>(parsed.named());
        List<String> tokens = parsed.tokens();
        if (applyMailboxShorthand(command.name(), tokens, arguments)) {
            return Map.copyOf(arguments);
        }
        if (applyAgentShorthand(command.name(), tokens, arguments)) {
            return Map.copyOf(arguments);
        }
        List<PromptParameter> parameters = command.parameters();
        List<String> positionals = parsed.positionals();
        for (int index = 0; index < positionals.size() && index < parameters.size(); index++) {
            arguments.putIfAbsent(parameters.get(index).name(), positionals.get(index));
        }
        return Map.copyOf(arguments);
    }

    private boolean applyMailboxShorthand(String commandName, List<String> tokens, Map<String, String> arguments) {
        if (!"mailbox".equals(commandName) || tokens.size() < 3 || tokens.get(1).contains("=") || tokens.get(2).contains("=")) {
            return false;
        }
        if (isMailboxCommandAction(tokens.get(1))) {
            arguments.put("action", tokens.get(1));
            arguments.put("mailId", tokens.get(2));
            return true;
        }
        return false;
    }

    private boolean applyAgentShorthand(String commandName, List<String> tokens, Map<String, String> arguments) {
        if (!"agent".equals(commandName) || tokens.size() < 3 || tokens.get(1).contains("=") || tokens.get(2).contains("=")) {
            return false;
        }
        if ("interrupt".equals(tokens.get(1))) {
            arguments.put("action", tokens.get(1));
            arguments.put("agentId", tokens.get(2));
            return true;
        }
        return false;
    }

    private boolean isMailboxCommandAction(String action) {
        return "accept".equals(action) || "stash".equals(action) || "discard".equals(action);
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

    private List<SlashCommand> safeSlashCommands(List<SlashCommand> commands) {
        return commands == null ? List.of() : List.copyOf(commands);
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
