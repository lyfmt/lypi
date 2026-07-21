package cn.lypi.runtime.subagent;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.model.ApiStyle;
import cn.lypi.contracts.model.CostProfile;
import cn.lypi.contracts.model.ModelCatalogPort;
import cn.lypi.contracts.model.ModelDescriptor;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.runtime.ChildSessionPort;
import cn.lypi.contracts.runtime.SessionManagerPort;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.security.PermissionRuntimeState;
import cn.lypi.contracts.session.AgentLifecycleEntry;
import cn.lypi.contracts.session.ChildSessionRequest;
import cn.lypi.contracts.session.ForkRequest;
import cn.lypi.contracts.session.SessionContext;
import cn.lypi.contracts.session.SessionEntry;
import cn.lypi.contracts.session.SessionHandle;
import cn.lypi.contracts.session.SessionView;
import cn.lypi.contracts.subagent.HeadlessSubagentInput;
import cn.lypi.contracts.subagent.HeadlessSubagentOutput;
import cn.lypi.contracts.subagent.MailboxStatus;
import cn.lypi.contracts.subagent.SubagentRunStatus;
import cn.lypi.contracts.subagent.SubagentSpawnRequest;
import cn.lypi.contracts.subagent.SubagentSpawnResult;
import cn.lypi.contracts.subagent.SubagentWaitRequest;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DefaultAgentCenterTest {
    private static final Instant NOW = Instant.parse("2026-06-09T00:00:00Z");
    private static final ModelSelection PARENT_MODEL = new ModelSelection("openai", "base", ThinkingLevel.MEDIUM);

    @TempDir
    Path tempDir;

    @Test
    void spawnSeparatesTaskAgentSessionRunAndLifecycleIdentity() {
        Fixture fixture = fixture(PermissionRuntimeState.forMode(PermissionMode.ASK), catalog(
            descriptor("openai", "base", true)
        ));

        SubagentSpawnResult result = fixture.center.spawn(request());

        AgentLifecycleEntry lifecycle = (AgentLifecycleEntry) fixture.parent.entries.getFirst();
        assertThat(result.status()).isEqualTo(SubagentRunStatus.STARTED);
        assertThat(result.taskName()).isEqualTo("inspect-session");
        assertThat(result.agentId()).startsWith("agent_");
        assertThat(result.childSessionId()).startsWith("ses_child_");
        assertThat(result.runId()).startsWith("run_");
        assertThat(Set.of(result.agentId(), result.childSessionId(), result.runId(), lifecycle.id())).hasSize(4);
        assertThat(lifecycle.metadata()).containsEntry("runId", result.runId());
        assertThat(fixture.process.input.taskName()).isEqualTo("inspect-session");
        assertThat(fixture.process.input.message()).isEqualTo("inspect the session module");
        assertThat(fixture.process.input.agentId()).isEqualTo(result.agentId());
        assertThat(fixture.process.input.runId()).isEqualTo(result.runId());
    }

    @Test
    void omittedModelFieldsInheritIndependentlyFromParent() {
        Fixture fixture = fixture(PermissionRuntimeState.forMode(PermissionMode.ASK), catalog(
            descriptor("anthropic", "base", true),
            descriptor("openai", "gpt-x", true),
            descriptor("openai", "base", true)
        ));

        fixture.center.spawn(request(Optional.of("anthropic"), Optional.empty(), Optional.empty()));
        assertThat(fixture.children.last().initialModel()).contains(
            new ModelSelection("anthropic", "base", ThinkingLevel.MEDIUM)
        );

        fixture.center.spawn(request(Optional.empty(), Optional.of("gpt-x"), Optional.empty()));
        assertThat(fixture.children.last().initialModel()).contains(
            new ModelSelection("openai", "gpt-x", ThinkingLevel.MEDIUM)
        );

        fixture.center.spawn(request(Optional.empty(), Optional.empty(), Optional.of(ThinkingLevel.HIGH)));
        assertThat(fixture.children.last().initialModel()).contains(
            new ModelSelection("openai", "base", ThinkingLevel.HIGH)
        );
    }

    @Test
    void explicitUnknownModelFailsBeforeCreatingChildSession() {
        Fixture fixture = fixture(PermissionRuntimeState.forMode(PermissionMode.ASK), catalog(
            descriptor("openai", "base", true)
        ));

        SubagentSpawnResult result = fixture.center.spawn(
            request(Optional.of("missing"), Optional.of("unknown"), Optional.empty())
        );

        assertThat(result.status()).isEqualTo(SubagentRunStatus.FAILED);
        assertThat(result.message()).hasValueSatisfying(message -> assertThat(message).contains("Unknown subagent model"));
        assertThat(fixture.children.requests).isEmpty();
        assertThat(fixture.parent.entries).isEmpty();
        assertThat(fixture.process.input).isNull();
    }

    @Test
    void explicitThinkingFailsWhenEffectiveModelDoesNotSupportIt() {
        Fixture fixture = fixture(PermissionRuntimeState.forMode(PermissionMode.ASK), catalog(
            descriptor("openai", "base", false)
        ));

        SubagentSpawnResult result = fixture.center.spawn(
            request(Optional.empty(), Optional.empty(), Optional.of(ThinkingLevel.HIGH))
        );

        assertThat(result.status()).isEqualTo(SubagentRunStatus.FAILED);
        assertThat(result.message()).hasValueSatisfying(message -> assertThat(message).contains("does not support thinking"));
        assertThat(fixture.children.requests).isEmpty();
    }

    @Test
    void childUsesParentCwdAndFixedAutoApprovalWithParentProfiles() {
        PermissionRuntimeState parentPermissions = PermissionRuntimeState.forMode(PermissionMode.BYPASS);
        Fixture fixture = fixture(parentPermissions, catalog(descriptor("openai", "base", true)));

        fixture.center.spawn(request());

        ChildSessionRequest child = fixture.children.last();
        PermissionRuntimeState childPermissions = child.initialPermissionRuntimeState();
        assertThat(child.cwd()).isEqualTo(tempDir);
        assertThat(child.sessionCwd()).isEqualTo(tempDir);
        assertThat(fixture.process.input.cwd()).isEqualTo(tempDir);
        assertThat(childPermissions.mode()).isEqualTo(PermissionMode.AUTO);
        assertThat(childPermissions.approvalPolicy())
            .isEqualTo(PermissionRuntimeState.forMode(PermissionMode.AUTO).approvalPolicy());
        assertThat(childPermissions.activePermissionProfile()).isEqualTo(parentPermissions.activePermissionProfile());
        assertThat(childPermissions.permissionProfile()).isEqualTo(parentPermissions.permissionProfile());
        assertThat(fixture.process.input.permissionRuntimeState()).isEqualTo(childPermissions);
    }

    @Test
    void completionCarriesStableIdentitiesIntoMailboxAndWaitConsumesIt() {
        Fixture fixture = fixture(PermissionRuntimeState.forMode(PermissionMode.ASK), catalog(
            descriptor("openai", "base", true)
        ));
        SubagentSpawnResult spawned = fixture.center.spawn(request());

        fixture.process.complete(new HeadlessSubagentOutput(
            spawned.taskName(),
            spawned.agentId(),
            spawned.childSessionId(),
            spawned.runId(),
            SubagentRunStatus.SUCCEEDED,
            "session inspection complete",
            Optional.of("entry_final"),
            Optional.empty()
        ));

        assertThat(fixture.mailbox.read("ses_parent", Set.of(MailboxStatus.PENDING)))
            .singleElement()
            .satisfies(message -> {
                assertThat(message.taskName()).isEqualTo(spawned.taskName());
                assertThat(message.agentId()).isEqualTo(spawned.agentId());
                assertThat(message.childSessionId()).isEqualTo(spawned.childSessionId());
                assertThat(message.runId()).isEqualTo(spawned.runId());
                assertThat(message.content()).isEqualTo("session inspection complete");
            });

        var waited = fixture.center.waitFor(new SubagentWaitRequest("ses_parent", 0));
        assertThat(waited.received()).isTrue();
        assertThat(waited.runId()).contains(spawned.runId());
        assertThat(waited.content()).contains("session inspection complete");
        assertThat(fixture.mailbox.poll("ses_parent")).isEmpty();
    }

    @Test
    void promptOnlyInputDoesNotCarryParentConversationOrTurnPermissionFields() {
        Fixture fixture = fixture(PermissionRuntimeState.forMode(PermissionMode.ASK), catalog(
            descriptor("openai", "base", true)
        ));

        fixture.center.spawn(request());

        assertThat(fixture.process.input.message()).isEqualTo("inspect the session module");
        assertThat(HeadlessSubagentInput.class.getRecordComponents())
            .extracting(java.lang.reflect.RecordComponent::getName)
            .doesNotContain("messages", "history", "additionalPermissions", "strictAutoReview");
    }

    private Fixture fixture(PermissionRuntimeState permissions, ModelCatalogPort modelCatalog) {
        ParentSession parent = new ParentSession(new SessionContext(
            List.of(),
            List.of("entry_parent"),
            List.of(),
            PARENT_MODEL,
            ThinkingLevel.MEDIUM,
            AgentMode.EXECUTE,
            permissions
        ));
        CapturingChildSessions children = new CapturingChildSessions();
        ControlledProcessRunner process = new ControlledProcessRunner();
        DefaultMailboxService mailbox = new DefaultMailboxService(
            new JsonlMailboxStore(tempDir),
            parent,
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
        DefaultAgentCenter center = new DefaultAgentCenter(
            List.of("lypi", "headless-subagent"),
            children,
            parent,
            tempDir,
            process,
            mailbox,
            modelCatalog,
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
        return new Fixture(center, parent, children, process, mailbox);
    }

    private SubagentSpawnRequest request() {
        return request(Optional.empty(), Optional.empty(), Optional.empty());
    }

    private SubagentSpawnRequest request(
        Optional<String> provider,
        Optional<String> model,
        Optional<ThinkingLevel> thinking
    ) {
        return new SubagentSpawnRequest(
            "ses_parent",
            "entry_parent",
            "inspect-session",
            "inspect the session module",
            List.of("read", "grep", "glob"),
            provider,
            model,
            thinking
        );
    }

    private ModelCatalogPort catalog(ModelDescriptor... descriptors) {
        List<ModelDescriptor> values = List.of(descriptors);
        return selection -> values.stream()
            .filter(value -> value.provider().equals(selection.provider()))
            .filter(value -> value.modelId().equals(selection.modelId()))
            .findFirst();
    }

    private ModelDescriptor descriptor(String provider, String model, boolean thinking) {
        return new ModelDescriptor(
            provider,
            model,
            URI.create("https://example.test"),
            ApiStyle.CUSTOM,
            128_000,
            8_192,
            thinking,
            false,
            new CostProfile(BigDecimal.ZERO, BigDecimal.ZERO, "USD"),
            Map.of()
        );
    }

    private record Fixture(
        DefaultAgentCenter center,
        ParentSession parent,
        CapturingChildSessions children,
        ControlledProcessRunner process,
        DefaultMailboxService mailbox
    ) {}

    private static final class CapturingChildSessions implements ChildSessionPort {
        private final List<ChildSessionRequest> requests = new ArrayList<>();

        @Override
        public SessionHandle create(ChildSessionRequest request) {
            requests.add(request);
            return null;
        }

        private ChildSessionRequest last() {
            return requests.getLast();
        }
    }

    private static final class ControlledProcessRunner implements SubagentProcessRunner {
        private final List<ControlledHandle> handles = new ArrayList<>();
        private HeadlessSubagentInput input;

        @Override
        public SubagentProcessHandle start(HeadlessSubagentInput input) {
            this.input = input;
            ControlledHandle handle = new ControlledHandle();
            handles.add(handle);
            return handle;
        }

        private void complete(HeadlessSubagentOutput output) {
            handles.getLast().completion.complete(output);
        }
    }

    private static final class ControlledHandle implements SubagentProcessHandle {
        private final CompletableFuture<HeadlessSubagentOutput> completion = new CompletableFuture<>();

        @Override
        public CompletableFuture<HeadlessSubagentOutput> completion() {
            return completion;
        }

        @Override
        public void interrupt() {
        }
    }

    private static final class ParentSession implements SessionManagerPort {
        private final SessionContext context;
        private final List<SessionEntry> entries = new ArrayList<>();
        private String leafId = "entry_parent";

        private ParentSession(SessionContext context) {
            this.context = context;
        }

        @Override
        public SessionHandle openOrCreate(String sessionId) {
            return null;
        }

        @Override
        public SessionHandle append(SessionEntry entry) {
            entries.add(entry);
            leafId = entry.id();
            return null;
        }

        @Override
        public SessionHandle switchLeaf(String leafId) {
            this.leafId = leafId;
            return null;
        }

        @Override
        public List<SessionEntry> branch(String leafId) {
            return List.copyOf(entries);
        }

        @Override
        public SessionView currentView() {
            return new SessionView("ses_parent", leafId);
        }

        @Override
        public SessionView view(String leafId) {
            return new SessionView("ses_parent", leafId);
        }

        @Override
        public List<AgentMessage> transcript(String leafId) {
            return context.messages();
        }

        @Override
        public SessionContext context(String leafId) {
            return context;
        }

        @Override
        public SessionHandle appendMessage(AgentMessage message) {
            return null;
        }

        @Override
        public SessionHandle fork(ForkRequest request) {
            return null;
        }
    }
}
