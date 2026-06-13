package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.agent.TurnRequest;
import cn.lypi.contracts.agent.TurnState;
import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContextBudget;
import cn.lypi.contracts.event.AgentEvent;
import cn.lypi.contracts.event.EventBus;
import cn.lypi.contracts.event.EventConsumer;
import cn.lypi.contracts.event.EventFilter;
import cn.lypi.contracts.event.EventSubscription;
import cn.lypi.contracts.event.ErrorEvent;
import cn.lypi.contracts.event.InterruptEvent;
import cn.lypi.contracts.event.MessageDeltaEvent;
import cn.lypi.contracts.event.MessageEndEvent;
import cn.lypi.contracts.event.MessageStartEvent;
import cn.lypi.contracts.event.PermissionResponseEvent;
import cn.lypi.contracts.event.SessionStateEvent;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.prompt.PromptParameter;
import cn.lypi.contracts.prompt.PromptTemplate;
import cn.lypi.contracts.prompt.PromptTemplateSource;
import cn.lypi.contracts.resource.ResourceSnapshot;
import cn.lypi.contracts.runtime.AgentCorePort;
import cn.lypi.contracts.runtime.CompactionResult;
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
import cn.lypi.contracts.skill.SkillDescriptor;
import cn.lypi.contracts.skill.SkillIndex;
import cn.lypi.contracts.skill.SkillMention;
import cn.lypi.contracts.skill.SkillSource;
import cn.lypi.contracts.tui.SessionRuntimeState;
import cn.lypi.contracts.tui.SlashCommand;
import cn.lypi.contracts.tui.SlashCommandHandler;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RuntimeTuiSubmitHandlerTest {
    @Test
    void submitCreatesIndependentAbortSignalPerTurn() {
        RecordingCore core = new RecordingCore();
        RecordingEventBus events = new RecordingEventBus();
        RuntimeTuiSubmitHandler handler = new RuntimeTuiSubmitHandler("ses_1", core, events, Runnable::run);

        handler.submitUserInput("first");
        TurnRequest first = core.requests.getFirst();
        handler.submitUserInput("second");
        TurnRequest second = core.requests.get(1);

        assertEquals("ses_1", first.sessionId());
        assertEquals("first", first.userInput());
        assertEquals("second", second.userInput());
        assertNotSame(first.abortSignal(), second.abortSignal());
        assertFalse(first.abortSignal().aborted());
        assertFalse(second.abortSignal().aborted());
    }

    @Test
    void resumeSessionChangesNextTurnSessionId() {
        RecordingCore core = new RecordingCore();
        RecordingEventBus events = new RecordingEventBus();
        RuntimeTuiSubmitHandler handler = new RuntimeTuiSubmitHandler("ses_1", core, events, Runnable::run);

        handler.resumeSession("ses_old", "leaf_old");
        handler.submitUserInput("hello");

        TurnRequest request = core.requests.getFirst();
        assertEquals("ses_old", request.sessionId());
        assertEquals("hello", request.userInput());
    }

    @Test
    void submitCarriesExplicitSkillMentionBindings() {
        RecordingCore core = new RecordingCore();
        RecordingEventBus events = new RecordingEventBus();
        RuntimeTuiSubmitHandler handler = new RuntimeTuiSubmitHandler("ses_1", core, events, Runnable::run);

        handler.submitUserInput("use $doc", List.of(new SkillMention("doc", Path.of("/tmp/doc/SKILL.md"))));

        TurnRequest request = core.requests.getFirst();
        assertEquals("use $doc", request.userInput());
        assertEquals(List.of(new SkillMention("doc", Path.of("/tmp/doc/SKILL.md"))), request.skillMentions());
    }

    @Test
    void submitResolvesUniqueBareSkillTokenWhenNoBindingExists() {
        RecordingCore core = new RecordingCore();
        RecordingEventBus events = new RecordingEventBus();
        RuntimeTuiSubmitHandler handler = new RuntimeTuiSubmitHandler(
            "ses_1",
            core,
            events,
            Runnable::run,
            null,
            null,
            skills("doc")
        );

        handler.submitUserInput("use $doc");

        assertEquals(List.of(new SkillMention("doc", Path.of("/tmp/doc/SKILL.md"))), core.requests.getFirst().skillMentions());
    }

    @Test
    void submitDoesNotResolveAmbiguousBareSkillToken() {
        RecordingCore core = new RecordingCore();
        RecordingEventBus events = new RecordingEventBus();
        RuntimeTuiSubmitHandler handler = new RuntimeTuiSubmitHandler(
            "ses_1",
            core,
            events,
            Runnable::run,
            null,
            null,
            skills("doc", "doc")
        );

        handler.submitUserInput("use $doc");

        assertTrue(core.requests.getFirst().skillMentions().isEmpty());
    }

    @Test
    void interruptOnlyAbortsCurrentActiveTurnAndPublishesEvent() {
        RecordingCore core = new RecordingCore();
        RecordingEventBus events = new RecordingEventBus();
        RuntimeTuiSubmitHandler handler = new RuntimeTuiSubmitHandler("ses_1", core, events, Runnable::run);

        handler.submitUserInput("first");
        TurnRequest first = core.requests.getFirst();
        handler.submitUserInput("second");
        TurnRequest second = core.requests.get(1);

        handler.requestInterrupt("ctrl-c");

        assertFalse(first.abortSignal().aborted());
        assertTrue(second.abortSignal().aborted());
        InterruptEvent event = assertInstanceOf(InterruptEvent.class, events.published.getFirst());
        assertEquals("ses_1", event.sessionId());
        assertEquals("ctrl-c", event.reason());
    }

    @Test
    void defaultSubmitDoesNotBlockOnCoreExecution() throws Exception {
        BlockingCore core = new BlockingCore();
        RecordingEventBus events = new RecordingEventBus();
        RuntimeTuiSubmitHandler handler = new RuntimeTuiSubmitHandler("ses_1", core, events);

        handler.submitUserInput("hello");

        assertTrue(core.started.await(2, TimeUnit.SECONDS));
        assertEquals(1, core.requests.size());
        core.release.countDown();
    }

    @Test
    void submitPermissionOptionPublishesResponseEvent() {
        RecordingCore core = new RecordingCore();
        RecordingEventBus events = new RecordingEventBus();
        RuntimeTuiSubmitHandler handler = new RuntimeTuiSubmitHandler("ses_1", core, events, Runnable::run);

        handler.submitPermissionOption("perm_toolu_1", "toolu_1", "allow_once");

        PermissionResponseEvent event = assertInstanceOf(PermissionResponseEvent.class, events.published.getFirst());
        assertEquals("ses_1", event.sessionId());
        assertEquals("perm_toolu_1", event.requestId());
        assertEquals("allow_once", event.selectedOptionId());
        assertEquals(false, event.fromKeyboardCancel());
    }

    @Test
    void stateSlashCommandDoesNotSubmitTurnAndAppendsSessionEntry() {
        RecordingCore core = new RecordingCore();
        RecordingEventBus events = new RecordingEventBus();
        RecordingSessionManager session = new RecordingSessionManager();
        RuntimeTuiSubmitHandler handler = new RuntimeTuiSubmitHandler(
            "ses_1",
            core,
            events,
            Runnable::run,
            new SlashCommandRouter("ses_1", Path.of("."), session, emptyResources())
        );

        handler.submitUserInput("/thinking high");

        assertEquals(List.of(), core.requests);
        ThinkingChangeEntry entry = assertInstanceOf(ThinkingChangeEntry.class, session.entries.getFirst());
        assertEquals(ThinkingLevel.HIGH, entry.thinkingLevel());
        SessionStateEvent state = assertInstanceOf(SessionStateEvent.class, events.published.getFirst());
        assertEquals(entry.id(), state.leafId());
        assertEquals(ThinkingLevel.HIGH, state.thinkingLevel());
        assertEquals(new ModelSelection("openai", "gpt-5", ThinkingLevel.HIGH), state.model());
        assertEquals(AgentMode.EXECUTE, state.agentMode());
        assertEquals(PermissionMode.DEFAULT_EXECUTE, state.permissionMode());
        MessageDeltaEvent delta = assertInstanceOf(MessageDeltaEvent.class, events.published.get(2));
        assertEquals("thinking: HIGH", delta.delta());
    }

    @Test
    void templateSlashCommandSubmitsRenderedPrompt() {
        RecordingCore core = new RecordingCore();
        RecordingEventBus events = new RecordingEventBus();
        RecordingSessionManager session = new RecordingSessionManager();
        RuntimeTuiSubmitHandler handler = new RuntimeTuiSubmitHandler(
            "ses_1",
            core,
            events,
            Runnable::run,
            new SlashCommandRouter("ses_1", Path.of("."), session, reviewResources())
        );

        handler.submitUserInput("/review scope=staged");

        assertEquals("Review staged.", core.requests.getFirst().userInput());
        assertEquals(List.of(), session.entries);
    }

    @Test
    void externalSlashCommandRunsHandlerAndPublishesLocalOutputWithoutStartingTurn() {
        RecordingCore core = new RecordingCore();
        RecordingEventBus events = new RecordingEventBus();
        RecordingSlashCommandHandler slash = new RecordingSlashCommandHandler("mailId: mail_1");
        RuntimeTuiSubmitHandler handler = new RuntimeTuiSubmitHandler(
            "ses_1",
            core,
            events,
            Runnable::run,
            List.of(new SlashCommand("mailbox", "读取 mailbox", List.of(), slash))
        );

        handler.submitUserInput("/mailbox accept mail_1");

        assertTrue(core.requests.isEmpty());
        assertEquals(Map.of("action", "accept", "mailId", "mail_1"), slash.arguments);
        MessageStartEvent start = assertInstanceOf(MessageStartEvent.class, events.published.getFirst());
        assertEquals("ses_1", start.sessionId());
        MessageDeltaEvent delta = assertInstanceOf(MessageDeltaEvent.class, events.published.get(1));
        assertEquals("ses_1", delta.sessionId());
        assertEquals("mailId: mail_1", delta.delta());
        assertInstanceOf(MessageEndEvent.class, events.published.get(2));
    }

    @Test
    void agentInterruptSlashCommandShorthandMapsAgentIdArgument() {
        RecordingCore core = new RecordingCore();
        RecordingEventBus events = new RecordingEventBus();
        RecordingSlashCommandHandler slash = new RecordingSlashCommandHandler("中断请求已发送: agent_1");
        RuntimeTuiSubmitHandler handler = new RuntimeTuiSubmitHandler(
            "ses_1",
            core,
            events,
            Runnable::run,
            List.of(new SlashCommand("agent", "管理 agent", List.of(), slash))
        );

        handler.submitUserInput("/agent interrupt agent_1");

        assertTrue(core.requests.isEmpty());
        assertEquals(Map.of("action", "interrupt", "agentId", "agent_1"), slash.arguments);
        MessageDeltaEvent delta = assertInstanceOf(MessageDeltaEvent.class, events.published.get(1));
        assertEquals("中断请求已发送: agent_1", delta.delta());
    }

    @Test
    void regularInputStillStartsTurnWhenSlashCommandsAreRegistered() {
        RecordingCore core = new RecordingCore();
        RecordingEventBus events = new RecordingEventBus();
        RuntimeTuiSubmitHandler handler = new RuntimeTuiSubmitHandler(
            "ses_1",
            core,
            events,
            Runnable::run,
            List.of(new SlashCommand("mailbox", "读取 mailbox", List.of(), new RecordingSlashCommandHandler("")))
        );

        handler.submitUserInput("hello");

        assertEquals("hello", core.requests.getFirst().userInput());
    }

    @Test
    void unknownSlashCommandStillSubmitsAsUserInput() {
        RecordingCore core = new RecordingCore();
        RecordingEventBus events = new RecordingEventBus();
        RecordingSessionManager session = new RecordingSessionManager();
        RuntimeTuiSubmitHandler handler = new RuntimeTuiSubmitHandler(
            "ses_1",
            core,
            events,
            Runnable::run,
            new SlashCommandRouter(
                "ses_1",
                Path.of("."),
                session,
                emptyResources(),
                null,
                List.of(new SlashCommand("mailbox", "读取 mailbox", List.of(), new RecordingSlashCommandHandler("")))
            )
        );

        handler.submitUserInput("/unknown text");

        assertEquals("/unknown text", core.requests.getFirst().userInput());
        assertEquals(List.of(), session.entries);
        assertTrue(events.published.isEmpty());
    }

    @Test
    void invalidSlashCommandPublishesVisibleErrorWithoutSubmittingTurn() {
        RecordingCore core = new RecordingCore();
        RecordingEventBus events = new RecordingEventBus();
        RecordingSessionManager session = new RecordingSessionManager();
        RuntimeTuiSubmitHandler handler = new RuntimeTuiSubmitHandler(
            "ses_1",
            core,
            events,
            Runnable::run,
            new SlashCommandRouter("ses_1", Path.of("."), session, emptyResources())
        );

        handler.submitUserInput("/thinking huge");

        assertEquals(List.of(), core.requests);
        ErrorEvent event = assertInstanceOf(ErrorEvent.class, events.published.getFirst());
        assertEquals("ses_1", event.sessionId());
        assertTrue(event.message().contains("unknown thinking level"));
    }

    @Test
    void compactSlashCommandPublishesVisibleResultWithoutSubmittingTurn() {
        RecordingCore core = new RecordingCore();
        RecordingEventBus events = new RecordingEventBus();
        RecordingSessionManager session = new RecordingSessionManager();
        RuntimeTuiSubmitHandler handler = new RuntimeTuiSubmitHandler(
            "ses_1",
            core,
            events,
            Runnable::run,
            new SlashCommandRouter(
                "ses_1",
                Path.of("."),
                session,
                emptyResources(),
                request -> new CompactionResult(true, Optional.of("entry-compact-1"), "compacted")
            )
        );

        handler.submitUserInput("/compact");

        assertEquals(List.of(), core.requests);
        MessageStartEvent start = assertInstanceOf(MessageStartEvent.class, events.published.getFirst());
        assertEquals("ses_1", start.sessionId());
        MessageDeltaEvent delta = assertInstanceOf(MessageDeltaEvent.class, events.published.get(1));
        assertEquals("ses_1", delta.sessionId());
        assertEquals("compact: compacted", delta.delta());
        assertInstanceOf(MessageEndEvent.class, events.published.get(2));
    }

    @Test
    void compactRunsInBackgroundAndRejectsUserInputUntilFinished() {
        RecordingCore core = new RecordingCore();
        RecordingEventBus events = new RecordingEventBus();
        RecordingSessionManager session = new RecordingSessionManager();
        QueuedExecutor executor = new QueuedExecutor();
        AtomicInteger compactionCalls = new AtomicInteger();
        RuntimeTuiSubmitHandler handler = new RuntimeTuiSubmitHandler(
            "ses_1",
            core,
            events,
            executor,
            new SlashCommandRouter(
                "ses_1",
                Path.of("/tmp/project"),
                session,
                emptyResources(),
                request -> {
                    compactionCalls.incrementAndGet();
                    return new CompactionResult(true, Optional.of("entry-compact-1"), "compacted");
                }
            )
        );

        handler.submitUserInput("/compact");
        handler.submitUserInput("hello while compacting");

        assertEquals(0, compactionCalls.get());
        assertEquals(1, executor.tasks.size());
        assertTrue(core.requests.isEmpty());
        ErrorEvent blocked = assertInstanceOf(ErrorEvent.class, events.published.getFirst());
        assertTrue(blocked.message().contains("compaction is running"));

        executor.runNext();

        assertEquals(1, compactionCalls.get());
        MessageDeltaEvent compactResult = events.published.stream()
            .filter(MessageDeltaEvent.class::isInstance)
            .map(MessageDeltaEvent.class::cast)
            .filter(event -> event.delta().contains("compact: compacted"))
            .findFirst()
            .orElseThrow();
        assertEquals("ses_1", compactResult.sessionId());

        handler.submitUserInput("after compact");
        assertEquals(1, executor.tasks.size());
        executor.runNext();

        assertEquals("after compact", core.requests.getFirst().userInput());
    }

    @Test
    void newSlashCommandSwitchesNextTurnSessionIdWithoutSubmittingTurn() {
        RecordingCore core = new RecordingCore();
        RecordingEventBus events = new RecordingEventBus();
        RecordingSessionManager session = new RecordingSessionManager();
        SessionRuntimeState newState = runtimeState("ses_new", "leaf_new");
        RuntimeTuiSubmitHandler handler = new RuntimeTuiSubmitHandler(
            "ses_1",
            core,
            events,
            Runnable::run,
            new SlashCommandRouter(
                "ses_1",
                Path.of("."),
                session,
                emptyResources(),
                null,
                () -> newState,
                List.of()
            )
        );

        handler.submitUserInput("/new");
        handler.submitUserInput("hello");

        assertEquals(1, core.requests.size());
        TurnRequest request = core.requests.getFirst();
        assertEquals("ses_new", request.sessionId());
        assertEquals("hello", request.userInput());
        MessageDeltaEvent delta = assertInstanceOf(MessageDeltaEvent.class, events.published.get(1));
        assertEquals("ses_new", delta.sessionId());
        assertEquals("new session: ses_new", delta.delta());
    }

    private static final class RecordingCore implements AgentCorePort {
        private final List<TurnRequest> requests = new ArrayList<>();

        @Override
        public TurnState execute(TurnRequest request) {
            requests.add(request);
            return null;
        }
    }

    private static final class BlockingCore implements AgentCorePort {
        private final List<TurnRequest> requests = new ArrayList<>();
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public TurnState execute(TurnRequest request) {
            requests.add(request);
            started.countDown();
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    private static final class QueuedExecutor implements java.util.concurrent.Executor {
        private final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        private void runNext() {
            tasks.removeFirst().run();
        }
    }

    private static final class RecordingSlashCommandHandler implements SlashCommandHandler {
        private final String output;
        private Map<String, String> arguments;

        private RecordingSlashCommandHandler(String output) {
            this.output = output;
        }

        @Override
        public void handle(Map<String, String> arguments) {
            this.arguments = arguments;
        }

        @Override
        public String lastOutput() {
            return output;
        }
    }

    private static final class RecordingEventBus implements EventBus {
        private final List<AgentEvent> published = new ArrayList<>();

        @Override
        public void publish(AgentEvent event) {
            published.add(event);
        }

        @Override
        public EventSubscription subscribe(EventFilter filter, EventConsumer consumer) {
            return () -> {
            };
        }
    }

    private static ResourceRuntimePort emptyResources() {
        return resources(List.of());
    }

    private static ResourceRuntimePort reviewResources() {
        PromptTemplate review = new PromptTemplate(
            "review",
            "Review changes",
            PromptTemplateSource.PROJECT,
            List.of(new PromptParameter("scope", "Review scope", true, Optional.empty())),
            "Review {{scope}}.",
            "sha256:review"
        );
        return resources(List.of(review));
    }

    private static ResourceRuntimePort resources(List<PromptTemplate> templates) {
        return new ResourceRuntimePort() {
            @Override
            public ResourceSnapshot load(Path cwd) {
                return new ResourceSnapshot(List.of(), List.of(), new cn.lypi.contracts.skill.SkillIndex(List.of(), List.of()), templates, List.of(), List.of());
            }

            @Override
            public cn.lypi.contracts.prompt.SystemPrompt buildSystemPrompt(ResourceSnapshot resources) {
                return null;
            }
        };
    }

    private static java.util.function.Supplier<SkillIndex> skills(String... names) {
        List<SkillDescriptor> descriptors = new ArrayList<>();
        for (int index = 0; index < names.length; index++) {
            String name = names[index];
            descriptors.add(new SkillDescriptor(
                name,
                "Skill " + name,
                SkillSource.PROJECT,
                Path.of("/tmp/" + name + (index == 0 ? "" : index) + "/SKILL.md"),
                List.of(),
                List.of(),
                "sha256:" + name + index
            ));
        }
        return () -> new SkillIndex(descriptors, List.of());
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

    private static final class RecordingSessionManager implements SessionManagerPort {
        private final List<SessionEntry> entries = new ArrayList<>();
        private String leafId = "root";

        @Override
        public SessionHandle openOrCreate(String sessionId) {
            return new SessionHandle(sessionId, Path.of("session.jsonl"), leafId, Map.of());
        }

        @Override
        public SessionHandle append(SessionEntry entry) {
            entries.add(entry);
            leafId = entry.id();
            return openOrCreate("ses_1");
        }

        @Override
        public SessionHandle switchLeaf(String leafId) {
            this.leafId = leafId;
            return openOrCreate("ses_1");
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
            ModelSelection model = new ModelSelection("openai", "gpt-5", ThinkingLevel.MEDIUM);
            ThinkingLevel thinkingLevel = ThinkingLevel.MEDIUM;
            AgentMode agentMode = AgentMode.EXECUTE;
            PermissionMode permissionMode = PermissionMode.DEFAULT_EXECUTE;
            for (SessionEntry entry : entries) {
                if (entry instanceof ModelChangeEntry modelChange) {
                    model = modelChange.model();
                } else if (entry instanceof ThinkingChangeEntry thinkingChange) {
                    thinkingLevel = thinkingChange.thinkingLevel();
                    model = new ModelSelection(model.provider(), model.modelId(), thinkingLevel);
                } else if (entry instanceof ModeChangeEntry modeChange) {
                    agentMode = modeChange.agentMode();
                } else if (entry instanceof PermissionModeChangeEntry permissionModeChange) {
                    permissionMode = permissionModeChange.permissionMode();
                }
            }
            return new SessionContext(
                List.of(),
                List.of(this.leafId),
                List.of(),
                model,
                thinkingLevel,
                agentMode,
                permissionMode
            );
        }

        @Override
        public SessionHandle appendMessage(AgentMessage message) {
            return openOrCreate("ses_1");
        }

        @Override
        public SessionHandle fork(ForkRequest request) {
            return openOrCreate("ses_1");
        }
    }
}
