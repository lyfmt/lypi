package cn.lypi.agent;

import cn.lypi.contracts.common.AbortSignal;
import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.TextContentBlock;
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
import cn.lypi.contracts.runtime.SessionEnginePort;
import cn.lypi.contracts.runtime.ToolRuntimePort;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionDecisionReason;
import cn.lypi.contracts.session.ForkRequest;
import cn.lypi.contracts.session.MessageEntry;
import cn.lypi.contracts.session.SessionEntry;
import cn.lypi.contracts.session.SessionHandle;
import cn.lypi.contracts.tool.Tool;
import cn.lypi.contracts.tool.ToolRegistrySnapshot;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseRequest;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
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

    static AgentMessage summaryMessage(String id, String text) {
        return textMessage(id, MessageRole.SYSTEM_LOCAL, MessageKind.SUMMARY, text);
    }

    static AgentMessage toolResultMessage(String id, String toolUseId, String text, boolean error) {
        return new AgentMessage(
            id,
            MessageRole.TOOL_RESULT,
            MessageKind.TOOL_RESULT,
            List.of(new ToolResultContentBlock(toolUseId, text, error)),
            NOW,
            Optional.empty(),
            Optional.empty()
        );
    }

    static ResourceRuntimePort fixedResourceRuntime(String systemPrompt) {
        ResourceSnapshot snapshot = new ResourceSnapshot(List.of(), List.of(), null, List.of(), List.of(), List.of());
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
        InMemorySessionEngine session,
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
            compactionCoordinator,
            memoryExtractionWorker
        );
    }

    static AgentCoreRuntimePorts ports(
        Path cwd,
        InMemorySessionEngine session,
        StubAiProvider aiProvider,
        StubToolRuntime toolRuntime,
        RecordingEventBus eventBus,
        ContextAssembler contextAssembler,
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

    static final class InMemorySessionEngine implements SessionEnginePort {
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
        public List<SessionEntry> pathToRoot(String leafId) {
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
            return path;
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

        void enqueue(List<AssistantStreamEvent> events) {
            streams.add(new ListAssistantEventStream(events));
        }

        void failWith(RuntimeException failure) {
            failures.add(failure);
        }

        @Override
        public AssistantEventStream stream(ContextSnapshot context, AbortSignal signal) {
            contexts.add(context);
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
        }

        @Override
        public Optional<Tool<?, ?>> resolve(String nameOrAlias) {
            return Optional.empty();
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
}
