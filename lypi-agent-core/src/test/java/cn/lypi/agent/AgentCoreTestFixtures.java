package cn.lypi.agent;

import cn.lypi.agent.compact.CompactionCoordinator;
import cn.lypi.agent.compact.NoopToolMicroCompactor;
import cn.lypi.agent.compact.ToolMicroCompactor;
import cn.lypi.contracts.common.AbortSignal;
import cn.lypi.contracts.common.JsonSchema;
import cn.lypi.contracts.common.ProgressSink;
import cn.lypi.contracts.common.ValidationResult;
import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.TextContentBlock;
import cn.lypi.contracts.context.ToolCallContentBlock;
import cn.lypi.contracts.context.ToolResultContentBlock;
import cn.lypi.contracts.event.AgentEvent;
import cn.lypi.contracts.event.EventBus;
import cn.lypi.contracts.event.EventConsumer;
import cn.lypi.contracts.event.EventFilter;
import cn.lypi.contracts.event.EventSubscription;
import cn.lypi.contracts.model.AssistantEventStream;
import cn.lypi.contracts.model.AssistantStreamEvent;
import cn.lypi.contracts.model.AssistantStreamResult;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.prompt.SystemPrompt;
import cn.lypi.contracts.resource.ResourceSnapshot;
import cn.lypi.contracts.runtime.AiProviderRuntimePort;
import cn.lypi.contracts.runtime.ResourceRuntimePort;
import cn.lypi.contracts.runtime.SecurityRuntimePort;
import cn.lypi.contracts.runtime.SessionManagerPort;
import cn.lypi.contracts.runtime.ToolRuntimePort;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionDecisionReason;
import cn.lypi.contracts.session.BranchSummaryEntry;
import cn.lypi.contracts.session.CustomMessageEntry;
import cn.lypi.contracts.session.ForkRequest;
import cn.lypi.contracts.session.CompactionEntry;
import cn.lypi.contracts.session.MessageEntry;
import cn.lypi.contracts.session.ModeChangeEntry;
import cn.lypi.contracts.session.ModelChangeEntry;
import cn.lypi.contracts.session.PermissionModeChangeEntry;
import cn.lypi.contracts.session.SessionContext;
import cn.lypi.contracts.session.SessionEntry;
import cn.lypi.contracts.session.SessionHandle;
import cn.lypi.contracts.session.SessionView;
import cn.lypi.contracts.session.ThinkingChangeEntry;
import cn.lypi.contracts.tool.Tool;
import cn.lypi.contracts.tool.InterruptBehavior;
import cn.lypi.contracts.tool.ToolRegistrySnapshot;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseContext;
import cn.lypi.contracts.tool.ToolUseRequest;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class AgentCoreTestFixtures {
    static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private AgentCoreTestFixtures() {
    }

    static AgentMessage userMessage(String id, String text) {
        return textMessage(id, MessageRole.USER, MessageKind.TEXT, text);
    }

    static AgentMessage assistantMessage(String id, String text) {
        return textMessage(id, MessageRole.ASSISTANT, MessageKind.TEXT, text);
    }

    static AgentMessage assistantToolCallMessage(String id, String toolUseId, String toolName, Map<String, Object> input) {
        return new AgentMessage(
            id,
            MessageRole.ASSISTANT,
            MessageKind.TOOL_CALL,
            List.of(new ToolCallContentBlock(
                toolUseId,
                toolName,
                "",
                Map.of("input", Map.copyOf(input), "complete", true)
            )),
            NOW,
            Optional.empty(),
            Optional.of("tool_calls")
        );
    }

    static AgentMessage summaryMessage(String id, String text) {
        return textMessage(id, MessageRole.USER, MessageKind.SUMMARY, text);
    }

    static AgentMessage summaryMessage(String id, String text, Instant timestamp) {
        return new AgentMessage(
            id,
            MessageRole.USER,
            MessageKind.SUMMARY,
            List.of(new TextContentBlock(text)),
            timestamp,
            Optional.empty(),
            Optional.empty()
        );
    }

    static AgentMessage toolResultMessage(String id, String toolUseId, String text, boolean error) {
        return toolResultMessage(id, toolUseId, text, error, Map.of());
    }

    static AgentMessage toolResultMessage(
        String id,
        String toolUseId,
        String text,
        boolean error,
        Map<String, Object> metadata
    ) {
        return new AgentMessage(
            id,
            MessageRole.TOOL_RESULT,
            MessageKind.TOOL_RESULT,
            List.of(new ToolResultContentBlock(toolUseId, text, error, metadata)),
            NOW,
            Optional.empty(),
            Optional.empty()
        );
    }

    static Tool<Map<String, Object>, String> tool(String name, List<String> aliases) {
        return new Tool<>() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public List<String> aliases() {
                return aliases;
            }

            @Override
            public JsonSchema inputSchema() {
                return new JsonSchema(Map.of());
            }

            @Override
            public ValidationResult validateInput(Map<String, Object> input, ToolUseContext context) {
                return new ValidationResult(true, List.of());
            }

            @Override
            public PermissionDecision checkPermissions(Map<String, Object> input, ToolUseContext context) {
                return new PermissionDecision(
                    PermissionBehavior.ALLOW,
                    PermissionDecisionReason.MODE_DEFAULT,
                    "allowed",
                    Optional.empty(),
                    Map.of()
                );
            }

            @Override
            public ToolResult<String> execute(Map<String, Object> input, ToolUseContext context, ProgressSink progress) {
                return new ToolResult<>("", false, List.of(), Optional.empty());
            }

            @Override
            public InterruptBehavior interruptBehavior() {
                return InterruptBehavior.CANCEL;
            }

            @Override
            public boolean isReadOnly(Map<String, Object> input) {
                return true;
            }

            @Override
            public boolean isConcurrencySafe(Map<String, Object> input) {
                return true;
            }

            @Override
            public boolean isDestructive(Map<String, Object> input) {
                return false;
            }

            @Override
            public int maxResultSize() {
                return 4096;
            }

            @Override
            public String renderForUser(Map<String, Object> input) {
                return name + " " + input;
            }

            @Override
            public AgentMessage serializeForContext(String output) {
                return toolResultMessage("msg_tool_result", "toolu_1", output, false);
            }
        };
    }

    static ResourceRuntimePort fixedResourceRuntime(String systemPrompt) {
        ResourceSnapshot snapshot = emptyResources();
        SystemPrompt prompt = new SystemPrompt(systemPrompt, List.of("test"), "hash");
        return new ResourceRuntimePort() {
            @Override
            public ResourceSnapshot load(Path cwd) {
                return snapshot;
            }

            @Override
            public SystemPrompt buildSystemPrompt(ResourceSnapshot resources) {
                return prompt;
            }
        };
    }

    static ResourceSnapshot emptyResources() {
        return new ResourceSnapshot(
            List.of(),
            List.of(),
            new cn.lypi.contracts.skill.SkillIndex(List.of(), List.of()),
            List.of(),
            List.of(),
            List.of()
        );
    }

    static ContextSnapshot minimalContext(List<AgentMessage> messages) {
        return new ContextSnapshot(
            new SystemPrompt("system", List.of("test"), "hash"),
            List.copyOf(messages),
            new ModelSelection("test", "gpt-test", ThinkingLevel.MEDIUM),
            ThinkingLevel.MEDIUM,
            cn.lypi.contracts.security.AgentMode.EXECUTE,
            cn.lypi.contracts.security.PermissionMode.DEFAULT_EXECUTE,
            new cn.lypi.contracts.context.ContextBudget(0, 128_000, 100_000, 8_192, 16_384, 0, 0, java.math.BigDecimal.ZERO)
        );
    }

    static SecurityRuntimePort allowAllSecurityRuntime() {
        return (request, context) -> new PermissionDecision(
            PermissionBehavior.ALLOW,
            PermissionDecisionReason.MODE_DEFAULT,
            "allowed",
            Optional.empty(),
            Map.of()
        );
    }

    static AgentCoreRuntimePorts ports(
        InMemorySessionManager session,
        StubAiProvider aiProvider,
        StubToolRuntime toolRuntime,
        RecordingEventBus eventBus,
        ContextAssembler contextAssembler,
        CompactionCoordinator compactionCoordinator,
        MemoryExtractionWorker memoryExtractionWorker
    ) {
        return ports(
            Path.of("."),
            session,
            aiProvider,
            toolRuntime,
            eventBus,
            contextAssembler,
            new NoopToolMicroCompactor(),
            compactionCoordinator,
            memoryExtractionWorker
        );
    }

    static AgentCoreRuntimePorts ports(
        InMemorySessionManager session,
        StubAiProvider aiProvider,
        StubToolRuntime toolRuntime,
        RecordingEventBus eventBus,
        ContextAssembler contextAssembler,
        ToolMicroCompactor toolMicroCompactor,
        CompactionCoordinator compactionCoordinator,
        MemoryExtractionWorker memoryExtractionWorker
    ) {
        return new AgentCoreRuntimePorts(
            Path.of("."),
            session,
            aiProvider,
            toolRuntime,
            allowAllSecurityRuntime(),
            fixedResourceRuntime("system"),
            eventBus,
            contextAssembler,
            toolMicroCompactor,
            compactionCoordinator,
            memoryExtractionWorker
        );
    }

    static AgentCoreRuntimePorts ports(
        Path cwd,
        InMemorySessionManager session,
        StubAiProvider aiProvider,
        StubToolRuntime toolRuntime,
        RecordingEventBus eventBus,
        ContextAssembler contextAssembler,
        CompactionCoordinator compactionCoordinator,
        MemoryExtractionWorker memoryExtractionWorker
    ) {
        return ports(
            cwd,
            session,
            aiProvider,
            toolRuntime,
            eventBus,
            contextAssembler,
            new NoopToolMicroCompactor(),
            compactionCoordinator,
            memoryExtractionWorker
        );
    }

    static AgentCoreRuntimePorts ports(
        Path cwd,
        InMemorySessionManager session,
        StubAiProvider aiProvider,
        StubToolRuntime toolRuntime,
        RecordingEventBus eventBus,
        ContextAssembler contextAssembler,
        ToolMicroCompactor toolMicroCompactor,
        CompactionCoordinator compactionCoordinator,
        MemoryExtractionWorker memoryExtractionWorker
    ) {
        return new AgentCoreRuntimePorts(
            cwd,
            session,
            aiProvider,
            toolRuntime,
            allowAllSecurityRuntime(),
            fixedResourceRuntime("system"),
            eventBus,
            contextAssembler,
            toolMicroCompactor,
            compactionCoordinator,
            memoryExtractionWorker
        );
    }

    private static AgentMessage textMessage(String id, MessageRole role, MessageKind kind, String text) {
        return new AgentMessage(
            id,
            role,
            kind,
            List.of(new TextContentBlock(text)),
            NOW,
            Optional.empty(),
            Optional.empty()
        );
    }

    static class InMemorySessionManager implements SessionManagerPort {
        private String sessionId;
        private String leafId = "";
        private final Map<String, SessionEntry> entries = new LinkedHashMap<>();

        @Override
        public SessionHandle openOrCreate(String sessionId) {
            this.sessionId = sessionId;
            return handle();
        }

        @Override
        public SessionHandle append(SessionEntry entry) {
            entries.put(entry.id(), entry);
            leafId = entry.id();
            return handle();
        }

        @Override
        public SessionHandle switchLeaf(String leafId) {
            this.leafId = leafId;
            return handle();
        }

        @Override
        public List<SessionEntry> branch(String leafId) {
            List<SessionEntry> path = new ArrayList<>();
            String current = leafId;
            while (current != null && !current.isBlank()) {
                SessionEntry entry = entries.get(current);
                if (entry == null) {
                    break;
                }
                path.add(entry);
                current = entry.parentId();
            }
            Collections.reverse(path);
            return List.copyOf(path);
        }

        @Override
        public SessionView currentView() {
            return view(leafId);
        }

        @Override
        public SessionView view(String leafId) {
            return new SessionView(sessionId, leafId);
        }

        @Override
        public List<AgentMessage> transcript(String leafId) {
            return context(leafId).messages();
        }

        @Override
        public SessionContext context(String leafId) {
            ModelSelection model = new ModelSelection("default", "default", ThinkingLevel.MEDIUM);
            ThinkingLevel thinkingLevel = ThinkingLevel.MEDIUM;
            cn.lypi.contracts.security.AgentMode mode = cn.lypi.contracts.security.AgentMode.EXECUTE;
            cn.lypi.contracts.security.PermissionMode permissionMode = cn.lypi.contracts.security.PermissionMode.DEFAULT_EXECUTE;
            List<AgentMessage> messages = new ArrayList<>();
            List<String> entryIds = new ArrayList<>();
            CompactionEntry latestCompaction = null;
            for (SessionEntry entry : branch(leafId)) {
                entryIds.add(entry.id());
                if (entry instanceof MessageEntry messageEntry) {
                    messages.add(messageEntry.message());
                } else if (entry instanceof BranchSummaryEntry branchSummary) {
                    messages.add(branchSummaryMessage(branchSummary));
                } else if (entry instanceof CustomMessageEntry customMessage) {
                    messages.add(customMessage(customMessage));
                } else if (entry instanceof CompactionEntry compactionEntry) {
                    latestCompaction = compactionEntry;
                } else if (entry instanceof ModelChangeEntry modelChange) {
                    model = modelChange.model();
                } else if (entry instanceof ThinkingChangeEntry thinkingChange) {
                    thinkingLevel = thinkingChange.thinkingLevel();
                    model = new ModelSelection(model.provider(), model.modelId(), thinkingLevel);
                } else if (entry instanceof ModeChangeEntry modeChange) {
                    mode = modeChange.agentMode();
                } else if (entry instanceof PermissionModeChangeEntry permissionChange) {
                    permissionMode = permissionChange.permissionMode();
                }
            }
            List<String> appliedCompactionEntryIds = List.of();
            if (latestCompaction != null) {
                messages = applyCompaction(messages, branch(leafId), latestCompaction);
                appliedCompactionEntryIds = List.of(latestCompaction.id());
            }
            return new SessionContext(
                List.copyOf(messages),
                List.copyOf(entryIds),
                appliedCompactionEntryIds,
                model,
                thinkingLevel,
                mode,
                permissionMode
            );
        }

        @Override
        public SessionHandle appendMessage(AgentMessage message) {
            String entryId = "entry-" + message.id();
            append(new MessageEntry(entryId, leafId, message, message.timestamp()));
            return handle();
        }

        @Override
        public SessionHandle fork(ForkRequest request) {
            throw new UnsupportedOperationException("测试替身未实现 fork");
        }

        String leafId() {
            return leafId;
        }

        List<AgentMessage> messages() {
            return entries.values().stream()
                .filter(MessageEntry.class::isInstance)
                .map(MessageEntry.class::cast)
                .map(MessageEntry::message)
                .toList();
        }

        SessionEntry entry(String entryId) {
            return entries.get(entryId);
        }

        SessionHandle handle() {
            return new SessionHandle(sessionId, Path.of("test-session.jsonl"), leafId, Map.copyOf(entries));
        }

        private List<AgentMessage> applyCompaction(
            List<AgentMessage> originalMessages,
            List<SessionEntry> branch,
            CompactionEntry compaction
        ) {
            List<AgentMessage> kept = new ArrayList<>();
            boolean keep = false;
            for (SessionEntry entry : branch) {
                if (entry.id().equals(compaction.firstKeptEntryId())) {
                    keep = true;
                }
                if (keep) {
                    project(entry).ifPresent(kept::add);
                }
            }
            List<AgentMessage> replay = new ArrayList<>();
            replay.add(summaryMessage("summary-" + compaction.id(), compaction.summary(), compaction.timestamp()));
            replay.addAll(kept.isEmpty() ? originalMessages : kept);
            return replay;
        }

        private Optional<AgentMessage> project(SessionEntry entry) {
            if (entry instanceof MessageEntry messageEntry) {
                return Optional.of(messageEntry.message());
            }
            if (entry instanceof BranchSummaryEntry branchSummary) {
                return Optional.of(branchSummaryMessage(branchSummary));
            }
            if (entry instanceof CustomMessageEntry customMessage) {
                return Optional.of(customMessage(customMessage));
            }
            return Optional.empty();
        }

        private AgentMessage branchSummaryMessage(BranchSummaryEntry branchSummary) {
            return textMessage(
                "branch-summary-" + branchSummary.id(),
                MessageRole.SYSTEM_LOCAL,
                MessageKind.SUMMARY,
                branchSummary.summary()
            );
        }

        private AgentMessage customMessage(CustomMessageEntry customMessage) {
            return textMessage(
                "custom-message-" + customMessage.id(),
                MessageRole.SYSTEM_LOCAL,
                MessageKind.TEXT,
                customMessage.content()
            );
        }
    }

    static final class RecordingEventBus implements EventBus {
        final List<AgentEvent> events = new ArrayList<>();

        @Override
        public void publish(AgentEvent event) {
            events.add(event);
        }

        @Override
        public EventSubscription subscribe(EventFilter filter, EventConsumer consumer) {
            return () -> {
            };
        }
    }

    static final class StubAiProvider implements AiProviderRuntimePort {
        private final List<AssistantEventStream> streams = new ArrayList<>();
        private final List<RuntimeException> failures = new ArrayList<>();
        final List<ContextSnapshot> contexts = new ArrayList<>();
        final List<AbortSignal> abortSignals = new ArrayList<>();

        void enqueue(List<AssistantStreamEvent> events) {
            streams.add(new ListAssistantEventStream(events));
        }

        void enqueueFailingAfter(List<AssistantStreamEvent> events, RuntimeException failure) {
            streams.add(new FailingAssistantEventStream(events, failure));
        }

        void failWith(RuntimeException failure) {
            failures.add(failure);
        }

        @Override
        public AssistantEventStream stream(ContextSnapshot context, AbortSignal signal) {
            contexts.add(context);
            abortSignals.add(signal);
            if (!failures.isEmpty()) {
                throw failures.removeFirst();
            }
            if (streams.isEmpty()) {
                throw new AssertionError("没有可用的测试模型流");
            }
            return streams.removeFirst();
        }
    }

    static final class StubToolRuntime implements ToolRuntimePort {
        final List<List<ToolUseRequest>> requests = new ArrayList<>();
        private final List<List<ToolResult<?>>> results = new ArrayList<>();
        private final List<RuntimeException> failures = new ArrayList<>();
        private final Map<String, Tool<?, ?>> toolsByNameOrAlias = new LinkedHashMap<>();
        private Path cwd = Path.of(".").toAbsolutePath().normalize();

        void enqueue(List<ToolResult<?>> result) {
            results.add(result);
        }

        void failWith(RuntimeException failure) {
            failures.add(failure);
        }

        void cwd(Path cwd) {
            this.cwd = cwd.toAbsolutePath().normalize();
        }

        @Override
        public void register(Tool<?, ?> tool) {
            toolsByNameOrAlias.put(tool.name(), tool);
            for (String alias : tool.aliases()) {
                toolsByNameOrAlias.put(alias, tool);
            }
        }

        @Override
        public Optional<Tool<?, ?>> resolve(String nameOrAlias) {
            return Optional.ofNullable(toolsByNameOrAlias.get(nameOrAlias));
        }

        @Override
        public ToolRegistrySnapshot snapshot() {
            return new ToolRegistrySnapshot(List.of());
        }

        @Override
        public Path cwd() {
            return cwd;
        }

        @Override
        public List<ToolResult<?>> execute(List<ToolUseRequest> requests, ContextSnapshot context) {
            this.requests.add(List.copyOf(requests));
            if (!failures.isEmpty()) {
                throw failures.removeFirst();
            }
            if (results.isEmpty()) {
                return List.of();
            }
            return results.removeFirst();
        }
    }

    static final class RecordingMemoryExtractionWorker implements MemoryExtractionWorker {
        int calls;
        RuntimeException failure;

        @Override
        public MemoryExtractionResult extractAfterTurn(cn.lypi.contracts.agent.TurnState state) {
            calls++;
            if (failure != null) {
                throw failure;
            }
            return new MemoryExtractionResult(List.of(), List.of(), List.of(), Optional.empty());
        }
    }

    static final class ListAssistantEventStream implements AssistantEventStream {
        private final List<AssistantStreamEvent> events;
        private boolean closed;

        ListAssistantEventStream(List<AssistantStreamEvent> events) {
            this.events = List.copyOf(events);
        }

        @Override
        public Iterator<AssistantStreamEvent> iterator() {
            return events.iterator();
        }

        @Override
        public AssistantStreamResult result() {
            return new AssistantStreamResult("", events, Optional.empty(), Optional.empty(), !closed, false, Optional.empty());
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    static final class FailingAssistantEventStream implements AssistantEventStream {
        private final List<AssistantStreamEvent> events;
        private final RuntimeException failure;
        private boolean closed;

        FailingAssistantEventStream(List<AssistantStreamEvent> events, RuntimeException failure) {
            this.events = List.copyOf(events);
            this.failure = failure;
        }

        @Override
        public Iterator<AssistantStreamEvent> iterator() {
            Iterator<AssistantStreamEvent> delegate = events.iterator();
            return new Iterator<>() {
                @Override
                public boolean hasNext() {
                    if (delegate.hasNext()) {
                        return true;
                    }
                    throw failure;
                }

                @Override
                public AssistantStreamEvent next() {
                    return delegate.next();
                }
            };
        }

        @Override
        public AssistantStreamResult result() {
            return new AssistantStreamResult("", events, Optional.empty(), Optional.empty(), !closed, false, Optional.empty());
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
