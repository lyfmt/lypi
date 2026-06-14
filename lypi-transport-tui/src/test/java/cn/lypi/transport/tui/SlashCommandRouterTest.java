package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContextBudget;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.prompt.PromptParameter;
import cn.lypi.contracts.prompt.PromptRenderRequest;
import cn.lypi.contracts.prompt.PromptRenderResult;
import cn.lypi.contracts.prompt.PromptTemplate;
import cn.lypi.contracts.prompt.PromptTemplateSource;
import cn.lypi.contracts.resource.ResourceSnapshot;
import cn.lypi.contracts.runtime.CompactionRequest;
import cn.lypi.contracts.runtime.CompactionResult;
import cn.lypi.contracts.runtime.CompactionRuntimePort;
import cn.lypi.contracts.runtime.ResourceRuntimePort;
import cn.lypi.contracts.runtime.SessionManagerPort;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.session.ForkRequest;
import cn.lypi.contracts.session.ModeChangeEntry;
import cn.lypi.contracts.session.ModelChangeEntry;
import cn.lypi.contracts.session.PermissionModeChangeEntry;
import cn.lypi.contracts.session.SessionContext;
import cn.lypi.contracts.session.SessionEntry;
import cn.lypi.contracts.session.SessionHandle;
import cn.lypi.contracts.session.SessionView;
import cn.lypi.contracts.session.ThinkingChangeEntry;
import cn.lypi.contracts.tui.NewSessionController;
import cn.lypi.contracts.tui.SessionRuntimeState;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SlashCommandRouterTest {
    @Test
    void routesThinkingChangeIntoSessionEntry() {
        RecordingSessionManager session = new RecordingSessionManager(context(
            new ModelSelection("openai", "gpt-5", ThinkingLevel.MEDIUM),
            ThinkingLevel.MEDIUM,
            AgentMode.EXECUTE,
            PermissionMode.DEFAULT_EXECUTE
        ));
        SlashCommandRouter router = new SlashCommandRouter("ses_1", Path.of("."), session, emptyResources());

        SlashCommandResult result = router.route("/thinking high");

        assertTrue(result.matched());
        assertTrue(result.consumed());
        assertTrue(result.prompt().isEmpty());
        assertEquals("thinking: HIGH", result.notice().orElseThrow());
        ThinkingChangeEntry entry = assertInstanceOf(ThinkingChangeEntry.class, session.entries.getFirst());
        assertEquals("root", entry.parentId());
        assertEquals(ThinkingLevel.HIGH, entry.thinkingLevel());
        assertEquals("/thinking high", entry.reason());
    }

    @Test
    void routesModePermissionAndModelChangesIntoSessionEntries() {
        RecordingSessionManager session = new RecordingSessionManager(context(
            new ModelSelection("openai", "gpt-5", ThinkingLevel.MEDIUM),
            ThinkingLevel.LOW,
            AgentMode.EXECUTE,
            PermissionMode.DEFAULT_EXECUTE
        ));
        SlashCommandRouter router = new SlashCommandRouter("ses_1", Path.of("."), session, emptyResources());

        router.route("/plan");
        router.route("/permission-mode accept-edits");
        router.route("/model anthropic/claude-sonnet-4");

        ModeChangeEntry mode = assertInstanceOf(ModeChangeEntry.class, session.entries.get(0));
        PermissionModeChangeEntry permission = assertInstanceOf(PermissionModeChangeEntry.class, session.entries.get(1));
        ModelChangeEntry model = assertInstanceOf(ModelChangeEntry.class, session.entries.get(2));
        assertEquals(AgentMode.PLAN, mode.agentMode());
        assertEquals(PermissionMode.ACCEPT_EDITS, permission.permissionMode());
        assertEquals(new ModelSelection("anthropic", "claude-sonnet-4", ThinkingLevel.LOW), model.model());
    }

    @Test
    void planCommandTogglesPlanBackToExecute() {
        RecordingSessionManager session = new RecordingSessionManager(context(
            new ModelSelection("openai", "gpt-5", ThinkingLevel.MEDIUM),
            ThinkingLevel.MEDIUM,
            AgentMode.PLAN,
            PermissionMode.DEFAULT_EXECUTE
        ));
        SlashCommandRouter router = new SlashCommandRouter("ses_1", Path.of("."), session, emptyResources());

        SlashCommandResult result = router.route("/plan");

        assertTrue(result.matched());
        assertTrue(result.consumed());
        assertEquals("mode: EXECUTE", result.notice().orElseThrow());
        ModeChangeEntry mode = assertInstanceOf(ModeChangeEntry.class, session.entries.getFirst());
        assertEquals("root", mode.parentId());
        assertEquals(AgentMode.EXECUTE, mode.agentMode());
        assertEquals("/plan", mode.reason());
    }

    @Test
    void singleModelArgumentKeepsCurrentProvider() {
        RecordingSessionManager session = new RecordingSessionManager(context(
            new ModelSelection("openai", "gpt-5", ThinkingLevel.HIGH),
            ThinkingLevel.HIGH,
            AgentMode.EXECUTE,
            PermissionMode.DEFAULT_EXECUTE
        ));
        SlashCommandRouter router = new SlashCommandRouter("ses_1", Path.of("."), session, emptyResources());

        router.route("/model gpt-5.4");

        ModelChangeEntry model = assertInstanceOf(ModelChangeEntry.class, session.entries.getFirst());
        assertEquals(new ModelSelection("openai", "gpt-5.4", ThinkingLevel.HIGH), model.model());
    }

    @Test
    void invalidModelProviderSyntaxIsConsumedWithErrorButDoesNotAppend() {
        RecordingSessionManager session = new RecordingSessionManager(context(
            new ModelSelection("openai", "gpt-5", ThinkingLevel.MEDIUM),
            ThinkingLevel.MEDIUM,
            AgentMode.EXECUTE,
            PermissionMode.DEFAULT_EXECUTE
        ));
        SlashCommandRouter router = new SlashCommandRouter("ses_1", Path.of("."), session, emptyResources());

        SlashCommandResult result = router.route("/model openai/");

        assertTrue(result.matched());
        assertTrue(result.consumed());
        assertTrue(result.message().orElseThrow().contains("usage: /model"));
        assertEquals(List.of(), session.entries);
    }

    @Test
    void stateCommandUsageOnlyListsSupportedModeValues() {
        RecordingSessionManager session = new RecordingSessionManager(context(
            new ModelSelection("openai", "gpt-5", ThinkingLevel.MEDIUM),
            ThinkingLevel.MEDIUM,
            AgentMode.EXECUTE,
            PermissionMode.DEFAULT_EXECUTE
        ));
        SlashCommandRouter router = new SlashCommandRouter("ses_1", Path.of("."), session, emptyResources());

        SlashCommandResult modeResult = router.route("/plan execute");
        SlashCommandResult permissionModeResult = router.route("/permission-mode");

        assertEquals("usage: /plan", modeResult.message().orElseThrow());
        assertEquals(
            "usage: /permission-mode <default-execute|accept-edits|bypass>",
            permissionModeResult.message().orElseThrow()
        );
    }

    @Test
    void invalidStateCommandIsConsumedWithErrorButDoesNotAppend() {
        RecordingSessionManager session = new RecordingSessionManager(context(
            new ModelSelection("openai", "gpt-5", ThinkingLevel.MEDIUM),
            ThinkingLevel.MEDIUM,
            AgentMode.EXECUTE,
            PermissionMode.DEFAULT_EXECUTE
        ));
        SlashCommandRouter router = new SlashCommandRouter("ses_1", Path.of("."), session, emptyResources());

        SlashCommandResult result = router.route("/thinking huge");

        assertTrue(result.matched());
        assertTrue(result.consumed());
        assertTrue(result.message().orElseThrow().contains("unknown thinking level"));
        assertEquals(List.of(), session.entries);
    }

    @Test
    void stateCommandUsesCurrentViewLeafEvenWhenOpenOrCreateResetsHandleLeaf() {
        RecordingSessionManager session = new RecordingSessionManager(context(
            new ModelSelection("openai", "gpt-5", ThinkingLevel.MEDIUM),
            ThinkingLevel.MEDIUM,
            AgentMode.EXECUTE,
            PermissionMode.DEFAULT_EXECUTE
        ));
        session.leafId = "selected";
        session.openLeafId = "latest";
        SlashCommandRouter router = new SlashCommandRouter("ses_1", Path.of("."), session, emptyResources());

        router.route("/model gpt-5.4");

        ModelChangeEntry entry = assertInstanceOf(ModelChangeEntry.class, session.entries.getFirst());
        assertEquals("selected", entry.parentId());
        assertEquals(new ModelSelection("openai", "gpt-5.4", ThinkingLevel.MEDIUM), entry.model());
        assertEquals(List.of("selected"), session.contextLeafIds);
    }

    @Test
    void unknownSlashCommandFallsThroughToModel() {
        RecordingSessionManager session = new RecordingSessionManager(context(
            new ModelSelection("openai", "gpt-5", ThinkingLevel.MEDIUM),
            ThinkingLevel.MEDIUM,
            AgentMode.EXECUTE,
            PermissionMode.DEFAULT_EXECUTE
        ));
        SlashCommandRouter router = new SlashCommandRouter("ses_1", Path.of("."), session, emptyResources());

        SlashCommandResult result = router.route("/unknown text");

        assertFalse(result.matched());
        assertFalse(result.consumed());
        assertEquals(List.of(), session.entries);
    }

    @Test
    void removedModeCommandDoesNotPrefixMatchModelCommand() {
        RecordingSessionManager session = new RecordingSessionManager(context(
            new ModelSelection("openai", "gpt-5", ThinkingLevel.MEDIUM),
            ThinkingLevel.MEDIUM,
            AgentMode.EXECUTE,
            PermissionMode.DEFAULT_EXECUTE
        ));
        SlashCommandRouter router = new SlashCommandRouter("ses_1", Path.of("."), session, emptyResources());

        SlashCommandResult result = router.route("/mode");

        assertFalse(result.matched());
        assertFalse(result.consumed());
        assertEquals(List.of(), session.entries);
    }

    @Test
    void compactCommandCallsCompactionRuntimeAndDoesNotAppendSessionEntry() {
        RecordingSessionManager session = new RecordingSessionManager(context(
            new ModelSelection("openai", "gpt-5", ThinkingLevel.MEDIUM),
            ThinkingLevel.MEDIUM,
            AgentMode.EXECUTE,
            PermissionMode.DEFAULT_EXECUTE
        ));
        RecordingCompactionRuntime compaction = new RecordingCompactionRuntime(new CompactionResult(
            true,
            Optional.of("entry-compact-1"),
            "compacted"
        ));
        SlashCommandRouter router = new SlashCommandRouter("ses_1", Path.of("/tmp/project"), session, emptyResources(), compaction);

        SlashCommandResult result = router.route("/compact");

        assertTrue(result.matched());
        assertTrue(result.consumed());
        assertEquals("compact: compacted", result.notice().orElseThrow());
        assertTrue(result.message().isEmpty());
        assertEquals("ses_1", compaction.request.sessionId());
        assertEquals(Optional.of("root"), compaction.request.leafEntryId());
        assertEquals(Path.of("/tmp/project"), compaction.request.cwd());
        assertEquals(List.of(), session.entries);
    }

    @Test
    void compactRuntimeFailureReturnsVisibleErrorWithoutThrowing() {
        RecordingSessionManager session = new RecordingSessionManager(context(
            new ModelSelection("openai", "gpt-5", ThinkingLevel.MEDIUM),
            ThinkingLevel.MEDIUM,
            AgentMode.EXECUTE,
            PermissionMode.DEFAULT_EXECUTE
        ));
        SlashCommandRouter router = new SlashCommandRouter(
            "ses_1",
            Path.of("."),
            session,
            emptyResources(),
            request -> {
                throw new IllegalStateException("summary disabled");
            }
        );

        SlashCommandResult result = router.route("/compact");

        assertTrue(result.matched());
        assertTrue(result.consumed());
        assertTrue(result.message().orElseThrow().contains("compact: summary disabled"));
        assertEquals(List.of(), session.entries);
    }

    @Test
    void newCommandCreatesNewSessionRuntimeStateWithoutAppendingEntry() {
        RecordingSessionManager session = new RecordingSessionManager(context(
            new ModelSelection("openai", "gpt-5", ThinkingLevel.MEDIUM),
            ThinkingLevel.MEDIUM,
            AgentMode.EXECUTE,
            PermissionMode.DEFAULT_EXECUTE
        ));
        SessionRuntimeState newState = runtimeState("ses_new", "leaf_new");
        RecordingNewSessionController newSession = new RecordingNewSessionController(newState);
        SlashCommandRouter router = new SlashCommandRouter(
            "ses_1",
            Path.of("."),
            session,
            emptyResources(),
            null,
            newSession,
            List.of()
        );

        SlashCommandResult result = router.route("/new");

        assertTrue(result.matched());
        assertTrue(result.consumed());
        assertEquals(1, newSession.calls);
        assertEquals(Optional.of(newState), result.runtimeState());
        assertEquals("new session: ses_new", result.notice().orElseThrow());
        assertEquals(List.of(), session.entries);
    }

    @Test
    void newCommandRejectsArguments() {
        RecordingSessionManager session = new RecordingSessionManager(context(
            new ModelSelection("openai", "gpt-5", ThinkingLevel.MEDIUM),
            ThinkingLevel.MEDIUM,
            AgentMode.EXECUTE,
            PermissionMode.DEFAULT_EXECUTE
        ));
        RecordingNewSessionController newSession = new RecordingNewSessionController(runtimeState("ses_new", "leaf_new"));
        SlashCommandRouter router = new SlashCommandRouter(
            "ses_1",
            Path.of("."),
            session,
            emptyResources(),
            null,
            newSession,
            List.of()
        );

        SlashCommandResult result = router.route("/new named");

        assertTrue(result.matched());
        assertTrue(result.consumed());
        assertTrue(result.message().orElseThrow().contains("usage: /new"));
        assertEquals(0, newSession.calls);
    }

    @Test
    void uniqueCommandPrefixExecutesMatchedBuiltInCommand() {
        RecordingSessionManager session = new RecordingSessionManager(context(
            new ModelSelection("openai", "gpt-5", ThinkingLevel.MEDIUM),
            ThinkingLevel.MEDIUM,
            AgentMode.EXECUTE,
            PermissionMode.DEFAULT_EXECUTE
        ));
        SlashCommandRouter router = new SlashCommandRouter("ses_1", Path.of("."), session, emptyResources());

        SlashCommandResult result = router.route("/think high");

        assertTrue(result.matched());
        assertTrue(result.consumed());
        ThinkingChangeEntry entry = assertInstanceOf(ThinkingChangeEntry.class, session.entries.getFirst());
        assertEquals(ThinkingLevel.HIGH, entry.thinkingLevel());
        assertEquals("/think high", entry.reason());
    }

    @Test
    void ambiguousCommandPrefixReturnsVisibleErrorWithoutAppend() {
        RecordingSessionManager session = new RecordingSessionManager(context(
            new ModelSelection("openai", "gpt-5", ThinkingLevel.MEDIUM),
            ThinkingLevel.MEDIUM,
            AgentMode.EXECUTE,
            PermissionMode.DEFAULT_EXECUTE
        ));
        PromptTemplate memory = new PromptTemplate(
            "memory",
            "Memory",
            PromptTemplateSource.PROJECT,
            List.of(),
            "Remember.",
            "sha256:memory"
        );
        SlashCommandRouter router = new SlashCommandRouter("ses_1", Path.of("."), session, resourcesWith(memory));

        SlashCommandResult result = router.route("/m value=x");

        assertTrue(result.matched());
        assertTrue(result.consumed());
        assertTrue(result.message().orElseThrow().contains("ambiguous slash command"));
        assertEquals(List.of(), session.entries);
    }

    @Test
    void rendersPromptTemplateSlashCommandIntoPrompt() {
        RecordingSessionManager session = new RecordingSessionManager(context(
            new ModelSelection("openai", "gpt-5", ThinkingLevel.MEDIUM),
            ThinkingLevel.MEDIUM,
            AgentMode.EXECUTE,
            PermissionMode.DEFAULT_EXECUTE
        ));
        SlashCommandRouter router = new SlashCommandRouter(
            "ses_1",
            Path.of("."),
            session,
            resourcesWith(reviewTemplate())
        );

        SlashCommandResult result = router.route("/review scope=\"staged diff\"");

        assertTrue(result.matched());
        assertFalse(result.consumed());
        assertEquals("Review staged diff.", result.prompt().orElseThrow());
        assertEquals(List.of(), session.entries);
    }

    @Test
    void promptTemplateSlashCommandUsesPromptRenderer() {
        RecordingSessionManager session = new RecordingSessionManager(context(
            new ModelSelection("openai", "gpt-5", ThinkingLevel.MEDIUM),
            ThinkingLevel.MEDIUM,
            AgentMode.EXECUTE,
            PermissionMode.DEFAULT_EXECUTE
        ));
        SlashCommandRouter router = new SlashCommandRouter(
            "ses_1",
            Path.of("."),
            session,
            resourcesWithRendered("rendered by renderer", reviewTemplate())
        );

        SlashCommandResult result = router.route("/review scope=worktree");

        assertTrue(result.matched());
        assertFalse(result.consumed());
        assertEquals("rendered by renderer", result.prompt().orElseThrow());
    }

    @Test
    void rendersPromptTemplateWhoseNameAlreadyStartsWithSlash() {
        RecordingSessionManager session = new RecordingSessionManager(context(
            new ModelSelection("openai", "gpt-5", ThinkingLevel.MEDIUM),
            ThinkingLevel.MEDIUM,
            AgentMode.EXECUTE,
            PermissionMode.DEFAULT_EXECUTE
        ));
        PromptTemplate template = new PromptTemplate(
            "/review",
            "Review changes",
            PromptTemplateSource.PROJECT,
            List.of(new PromptParameter("scope", "Review scope", true, Optional.empty())),
            "Review {{scope}}.",
            "sha256:review"
        );
        SlashCommandRouter router = new SlashCommandRouter("ses_1", Path.of("."), session, resourcesWith(template));

        SlashCommandResult result = router.route("/review scope=worktree");

        assertTrue(result.matched());
        assertFalse(result.consumed());
        assertEquals("Review worktree.", result.prompt().orElseThrow());
        assertEquals(List.of(), session.entries);
    }

    @Test
    void missingRequiredPromptTemplateParameterIsConsumedWithError() {
        RecordingSessionManager session = new RecordingSessionManager(context(
            new ModelSelection("openai", "gpt-5", ThinkingLevel.MEDIUM),
            ThinkingLevel.MEDIUM,
            AgentMode.EXECUTE,
            PermissionMode.DEFAULT_EXECUTE
        ));
        SlashCommandRouter router = new SlashCommandRouter(
            "ses_1",
            Path.of("."),
            session,
            resourcesWith(reviewTemplate())
        );

        SlashCommandResult result = router.route("/review");

        assertTrue(result.matched());
        assertTrue(result.consumed());
        assertTrue(result.message().orElseThrow().contains("missing required parameter: scope"));
        assertEquals(List.of(), session.entries);
    }

    @Test
    void memoryLintDefaultsToProjectLayers() {
        RecordingSessionManager session = new RecordingSessionManager(context(
            new ModelSelection("openai", "gpt-5", ThinkingLevel.MEDIUM),
            ThinkingLevel.MEDIUM,
            AgentMode.EXECUTE,
            PermissionMode.DEFAULT_EXECUTE
        ));
        SlashCommandRouter router = new SlashCommandRouter("ses_1", Path.of("."), session, resourcesWith(memoryLintTemplate()));

        SlashCommandResult result = router.route("/memory lint");

        assertTrue(result.matched());
        assertFalse(result.consumed());
        assertEquals("Lint L2,L3 with $memory-lint.", result.prompt().orElseThrow());
    }

    @Test
    void memoryLintAcceptsExplicitLayers() {
        RecordingSessionManager session = new RecordingSessionManager(context(
            new ModelSelection("openai", "gpt-5", ThinkingLevel.MEDIUM),
            ThinkingLevel.MEDIUM,
            AgentMode.EXECUTE,
            PermissionMode.DEFAULT_EXECUTE
        ));
        SlashCommandRouter router = new SlashCommandRouter("ses_1", Path.of("."), session, resourcesWith(memoryLintTemplate()));

        assertEquals("Lint L0,L3 with $memory-lint.", router.route("/memory lint L0 L3").prompt().orElseThrow());
        assertEquals("Lint L1,L2 with $memory-lint.", router.route("/memory lint --layers=L1,L2").prompt().orElseThrow());
    }

    @Test
    void memoryLintRejectsInvalidLayers() {
        RecordingSessionManager session = new RecordingSessionManager(context(
            new ModelSelection("openai", "gpt-5", ThinkingLevel.MEDIUM),
            ThinkingLevel.MEDIUM,
            AgentMode.EXECUTE,
            PermissionMode.DEFAULT_EXECUTE
        ));
        SlashCommandRouter router = new SlashCommandRouter("ses_1", Path.of("."), session, resourcesWith(memoryLintTemplate()));

        SlashCommandResult result = router.route("/memory lint L5");

        assertTrue(result.matched());
        assertTrue(result.consumed());
        assertTrue(result.message().orElseThrow().contains("usage: /memory lint"));
        assertEquals(List.of(), session.entries);
    }

    @Test
    void memoryLintRejectsUnknownNamedArgumentsAndEmptyLayers() {
        RecordingSessionManager session = new RecordingSessionManager(context(
            new ModelSelection("openai", "gpt-5", ThinkingLevel.MEDIUM),
            ThinkingLevel.MEDIUM,
            AgentMode.EXECUTE,
            PermissionMode.DEFAULT_EXECUTE
        ));
        SlashCommandRouter router = new SlashCommandRouter("ses_1", Path.of("."), session, resourcesWith(memoryLintTemplate()));

        SlashCommandResult unknownNamed = router.route("/memory lint foo=bar");
        SlashCommandResult emptyLayers = router.route("/memory lint --layers=");

        assertTrue(unknownNamed.matched());
        assertTrue(unknownNamed.consumed());
        assertTrue(unknownNamed.message().orElseThrow().contains("usage: /memory lint"));
        assertTrue(emptyLayers.matched());
        assertTrue(emptyLayers.consumed());
        assertTrue(emptyLayers.message().orElseThrow().contains("usage: /memory lint"));
        assertEquals(List.of(), session.entries);
    }

    @Test
    void memoryLintRequiresPromptTemplate() {
        RecordingSessionManager session = new RecordingSessionManager(context(
            new ModelSelection("openai", "gpt-5", ThinkingLevel.MEDIUM),
            ThinkingLevel.MEDIUM,
            AgentMode.EXECUTE,
            PermissionMode.DEFAULT_EXECUTE
        ));
        SlashCommandRouter router = new SlashCommandRouter("ses_1", Path.of("."), session, resourcesWith());

        SlashCommandResult result = router.route("/memory lint");

        assertTrue(result.matched());
        assertTrue(result.consumed());
        assertTrue(result.message().orElseThrow().contains("memory lint: prompt template memory-lint is unavailable"));
        assertEquals(List.of(), session.entries);
    }

    private static SessionContext context(
        ModelSelection model,
        ThinkingLevel thinking,
        AgentMode mode,
        PermissionMode permissionMode
    ) {
        return new SessionContext(List.of(), List.of("root"), List.of(), model, thinking, mode, permissionMode);
    }

    private static ResourceRuntimePort emptyResources() {
        return resourcesWith();
    }

    private static ResourceRuntimePort resourcesWith(PromptTemplate... templates) {
        return new ResourceRuntimePort() {
            @Override
            public ResourceSnapshot load(Path cwd) {
                return new ResourceSnapshot(List.of(), List.of(), new cn.lypi.contracts.skill.SkillIndex(List.of(), List.of()), List.of(templates), List.of(), List.of());
            }

            @Override
            public cn.lypi.contracts.prompt.SystemPrompt buildSystemPrompt(ResourceSnapshot resources) {
                return null;
            }

            @Override
            public PromptRenderResult renderPrompt(PromptTemplate template, PromptRenderRequest request) {
                String content = template.templateBody();
                for (PromptParameter parameter : template.parameters()) {
                    String value = request.arguments().get(parameter.name());
                    if (value == null) {
                        value = parameter.defaultValue().orElse(null);
                    }
                    if (value == null && parameter.required()) {
                        return new PromptRenderResult("", List.of(new cn.lypi.contracts.resource.ResourceDiagnostic(
                            cn.lypi.contracts.resource.ResourceDiagnosticLevel.WARNING,
                            "missing required parameter: " + parameter.name(),
                            Optional.empty()
                        )));
                    }
                    if (value != null) {
                        content = content.replace("{{" + parameter.name() + "}}", value);
                    }
                }
                return new PromptRenderResult(content, List.of());
            }
        };
    }

    private static ResourceRuntimePort resourcesWithRendered(String rendered, PromptTemplate... templates) {
        return new ResourceRuntimePort() {
            @Override
            public ResourceSnapshot load(Path cwd) {
                return new ResourceSnapshot(List.of(), List.of(), new cn.lypi.contracts.skill.SkillIndex(List.of(), List.of()), List.of(templates), List.of(), List.of());
            }

            @Override
            public cn.lypi.contracts.prompt.SystemPrompt buildSystemPrompt(ResourceSnapshot resources) {
                return null;
            }

            @Override
            public PromptRenderResult renderPrompt(PromptTemplate template, PromptRenderRequest request) {
                return new PromptRenderResult(rendered, List.of());
            }
        };
    }

    private static PromptTemplate reviewTemplate() {
        return new PromptTemplate(
            "review",
            "Review changes",
            PromptTemplateSource.PROJECT,
            List.of(new PromptParameter("scope", "Review scope", true, Optional.empty())),
            "Review {{scope}}.",
            "sha256:review"
        );
    }

    private static PromptTemplate memoryLintTemplate() {
        return new PromptTemplate(
            "memory-lint",
            "Memory lint",
            PromptTemplateSource.USER,
            List.of(new PromptParameter("layers", "Layers", true, Optional.empty())),
            "Lint {{layers}} with $memory-lint.",
            "sha256:memory-lint"
        );
    }

    private static SessionRuntimeState runtimeState(String sessionId, String leafId) {
        return new SessionRuntimeState(
            sessionId,
            Path.of("."),
            leafId,
            new ModelSelection("openai", "gpt-5", ThinkingLevel.MEDIUM),
            ThinkingLevel.MEDIUM,
            AgentMode.EXECUTE,
            PermissionMode.DEFAULT_EXECUTE,
            new ContextBudget(0, 128_000, 100_000, 8_192, 16_384, 0, 0, BigDecimal.ZERO),
            false,
            false,
            false,
            false
        );
    }

    private static final class RecordingNewSessionController implements NewSessionController {
        private final SessionRuntimeState state;
        private int calls;

        private RecordingNewSessionController(SessionRuntimeState state) {
            this.state = state;
        }

        @Override
        public SessionRuntimeState createNewSession() {
            calls++;
            return state;
        }
    }

    private static final class RecordingSessionManager implements SessionManagerPort {
        private final List<SessionEntry> entries = new ArrayList<>();
        private final List<String> contextLeafIds = new ArrayList<>();
        private final Map<String, SessionEntry> byId = new LinkedHashMap<>();
        private final SessionContext context;
        private String leafId = "root";
        private String openLeafId = "";

        private RecordingSessionManager(SessionContext context) {
            this.context = context;
        }

        @Override
        public SessionHandle openOrCreate(String sessionId) {
            return handle(sessionId, openLeafId.isBlank() ? leafId : openLeafId);
        }

        @Override
        public SessionHandle append(SessionEntry entry) {
            entries.add(entry);
            byId.put(entry.id(), entry);
            leafId = entry.id();
            return handle("ses_1");
        }

        @Override
        public SessionHandle switchLeaf(String leafId) {
            this.leafId = leafId;
            return handle("ses_1");
        }

        @Override
        public List<SessionEntry> branch(String leafId) {
            return List.copyOf(entries);
        }

        @Override
        public SessionView currentView() {
            return new SessionView("ses_1", leafId);
        }

        @Override
        public SessionView view(String leafId) {
            return new SessionView("ses_1", leafId);
        }

        @Override
        public List<AgentMessage> transcript(String leafId) {
            return List.of();
        }

        @Override
        public SessionContext context(String leafId) {
            contextLeafIds.add(leafId);
            return context;
        }

        @Override
        public SessionHandle appendMessage(AgentMessage message) {
            return handle("ses_1");
        }

        @Override
        public SessionHandle fork(ForkRequest request) {
            return handle("ses_1");
        }

        private SessionHandle handle(String sessionId) {
            return new SessionHandle(sessionId, Path.of("session.jsonl"), leafId, Map.copyOf(byId));
        }

        private SessionHandle handle(String sessionId, String leafId) {
            return new SessionHandle(sessionId, Path.of("session.jsonl"), leafId, Map.copyOf(byId));
        }
    }

    private static final class RecordingCompactionRuntime implements CompactionRuntimePort {
        private final CompactionResult result;
        private CompactionRequest request;

        private RecordingCompactionRuntime(CompactionResult result) {
            this.result = result;
        }

        @Override
        public CompactionResult compact(CompactionRequest request) {
            this.request = request;
            return result;
        }
    }
}
