package cn.lypi.runtime.subagent;

import cn.lypi.contracts.model.ModelCatalogPort;
import cn.lypi.contracts.model.ModelDescriptor;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.runtime.AgentCenterPort;
import cn.lypi.contracts.runtime.ChildSessionPort;
import cn.lypi.contracts.runtime.SessionManagerPort;
import cn.lypi.contracts.security.ApprovalPolicy;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.security.PermissionRuntimeState;
import cn.lypi.contracts.session.AgentLifecycleEntry;
import cn.lypi.contracts.session.ChildSessionRequest;
import cn.lypi.contracts.session.CustomEntry;
import cn.lypi.contracts.session.SessionContext;
import cn.lypi.contracts.subagent.HeadlessSubagentInput;
import cn.lypi.contracts.subagent.HeadlessSubagentOutput;
import cn.lypi.contracts.subagent.MailboxCommandResult;
import cn.lypi.contracts.subagent.SubagentRunStatus;
import cn.lypi.contracts.subagent.SubagentSpawnRequest;
import cn.lypi.contracts.subagent.SubagentSpawnResult;
import cn.lypi.contracts.subagent.SubagentToolPolicy;
import cn.lypi.contracts.subagent.SubagentWaitRequest;
import cn.lypi.contracts.subagent.SubagentWaitResult;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class DefaultAgentCenter implements AgentCenterPort, RunningAgentSnapshotProvider {
    private static final int DEFAULT_RUN_TIMEOUT_SECONDS = 1_200;

    private final List<String> command;
    private final ChildSessionPort childSessions;
    private final SessionManagerPort parentSession;
    private final Path parentCwd;
    private final SubagentProcessRunner processRunner;
    private final DefaultMailboxService mailbox;
    private final ModelCatalogPort modelCatalog;
    private final Clock clock;
    private final SubagentRunResultProjector resultProjector;
    private final Map<String, SubagentAgent> agentsById = new ConcurrentHashMap<>();
    private final Map<String, RunningSubagentRun> runsById = new ConcurrentHashMap<>();
    private final Map<String, String> runIdByAgentId = new ConcurrentHashMap<>();

    public DefaultAgentCenter(
        List<String> command,
        ChildSessionPort childSessions,
        SessionManagerPort parentSession,
        Path parentCwd,
        SubagentProcessRunner processRunner,
        DefaultMailboxService mailbox,
        ModelCatalogPort modelCatalog,
        Clock clock
    ) {
        this.command = command == null ? List.of() : List.copyOf(command);
        this.childSessions = java.util.Objects.requireNonNull(childSessions, "childSessions must not be null");
        this.parentSession = java.util.Objects.requireNonNull(parentSession, "parentSession must not be null");
        this.parentCwd = java.util.Objects.requireNonNull(parentCwd, "parentCwd must not be null");
        this.processRunner = java.util.Objects.requireNonNull(processRunner, "processRunner must not be null");
        this.mailbox = java.util.Objects.requireNonNull(mailbox, "mailbox must not be null");
        this.modelCatalog = modelCatalog;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.resultProjector = new SubagentRunResultProjector(this.clock, this::randomId);
    }

    @Override
    public SubagentSpawnResult spawn(SubagentSpawnRequest request) {
        String taskName = request == null ? "" : safe(request.taskName());
        try {
            validateRequest(request);
            if (command.isEmpty()) {
                return failedSpawn(taskName, subagentCommandMissingMessage());
            }
            SessionContext parentContext = parentSession.context(resolveParentEntryId(request.parentEntryId()));
            ModelSelection effectiveModel = effectiveModel(request, parentContext);
            PermissionRuntimeState childPermissions = childPermissions(parentContext.permissionRuntimeState());
            String agentId = "agent_" + randomId();
            String childSessionId = "ses_child_" + randomId();
            String runId = "run_" + randomId();
            String lifecycleEntryId = "entry_spawn_" + randomId();
            SubagentAgent agent = new SubagentAgent(
                agentId,
                request.taskName(),
                childSessionId,
                request.parentSessionId(),
                lifecycleEntryId,
                parentCwd
            );
            SubagentToolPolicy toolPolicy = new SubagentToolPolicy(request.tools(), request.tools());

            childSessions.create(new ChildSessionRequest(
                childSessionId,
                request.parentSessionId(),
                lifecycleEntryId,
                parentCwd,
                parentCwd,
                1,
                Optional.of(request.taskName()),
                Optional.empty(),
                Optional.of(effectiveModel),
                Optional.of(effectiveModel.thinkingLevel()),
                Optional.ofNullable(parentContext.mode()),
                childPermissions,
                toolPolicy
            ));
            parentSession.append(new AgentLifecycleEntry(
                lifecycleEntryId,
                resolveParentEntryId(request.parentEntryId()),
                agentId,
                childSessionId,
                request.parentSessionId(),
                "spawned",
                Map.of(
                    "taskName", request.taskName(),
                    "runId", runId,
                    "message", request.message(),
                    "provider", effectiveModel.provider(),
                    "model", effectiveModel.modelId(),
                    "thinkingLevel", effectiveModel.thinkingLevel().name()
                ),
                Instant.now(clock)
            ));
            HeadlessSubagentInput input = new HeadlessSubagentInput(
                request.taskName(),
                agentId,
                childSessionId,
                runId,
                request.parentSessionId(),
                lifecycleEntryId,
                request.message(),
                parentCwd,
                parentCwd,
                toolPolicy,
                childPermissions,
                DEFAULT_RUN_TIMEOUT_SECONDS
            );
            agentsById.put(agentId, agent);
            RunningSubagentRun running;
            try {
                SubagentProcessHandle handle = processRunner.start(input);
                running = new RunningSubagentRun(runId, lifecycleEntryId, agent, handle);
                runsById.put(runId, running);
                runIdByAgentId.put(agentId, runId);
                handle.completion().whenComplete((output, failure) -> complete(runId, output, failure));
            } catch (RuntimeException exception) {
                running = new RunningSubagentRun(runId, lifecycleEntryId, agent, null);
                completeStartedRun(running, failedOutput(running, exception));
                return failedSpawn(request.taskName(), agent, runId, exception.getMessage());
            }
            return new SubagentSpawnResult(
                request.taskName(),
                agentId,
                childSessionId,
                runId,
                SubagentRunStatus.STARTED,
                Optional.of("subagent started")
            );
        } catch (IllegalArgumentException exception) {
            return failedSpawn(taskName, exception.getMessage());
        }
    }

    @Override
    public SubagentWaitResult waitFor(SubagentWaitRequest request) {
        if (request == null || !parentSession.currentView().sessionId().equals(request.parentSessionId())) {
            return SubagentWaitResult.timedOut();
        }
        return mailbox.waitAndConsume(request.parentSessionId(), request.timeoutMillis());
    }

    @Override
    public MailboxCommandResult interrupt(String agentId) {
        String runId = runIdByAgentId.get(agentId);
        RunningSubagentRun running = runId == null ? null : runsById.get(runId);
        if (running == null || running.handle() == null) {
            return MailboxCommandResult.failure("Agent is not running: " + agentId);
        }
        parentSession.append(new CustomEntry(
            "entry_agent_command_" + randomId(),
            parentSession.currentView().leafId(),
            "agent_command",
            Map.of("action", "interrupt", "agentId", agentId, "runId", runId),
            Instant.now(clock)
        ));
        running.handle().interrupt();
        return MailboxCommandResult.success(null);
    }

    @Override
    public List<RunningAgentSnapshot> runningAgents(String parentSessionId) {
        return runsById.values().stream()
            .filter(run -> parentSessionId != null && parentSessionId.equals(run.agent().parentSessionId()))
            .map(run -> new RunningAgentSnapshot(
                run.agent().agentId(),
                run.agent().taskName(),
                run.agent().childSessionId(),
                run.runId(),
                run.agent().parentSessionId(),
                run.lifecycleEntryId()
            ))
            .toList();
    }

    private void complete(String runId, HeadlessSubagentOutput output, Throwable failure) {
        RunningSubagentRun running = runsById.remove(runId);
        if (running == null) {
            return;
        }
        runIdByAgentId.remove(running.agent().agentId(), runId);
        HeadlessSubagentOutput safeOutput = output == null ? failedOutput(running, failure) : output;
        completeStartedRun(running, safeOutput);
    }

    private void completeStartedRun(RunningSubagentRun running, HeadlessSubagentOutput output) {
        parentSession.append(resultProjector.lifecycleEntry(running, output));
        mailbox.publish(resultProjector.mailboxMessage(running, output));
    }

    private ModelSelection effectiveModel(SubagentSpawnRequest request, SessionContext parentContext) {
        ModelSelection parentModel = parentContext.model();
        if (parentModel == null) {
            throw new IllegalArgumentException("Parent session model is unavailable");
        }
        String provider = request.provider().orElse(parentModel.provider());
        String model = request.model().orElse(parentModel.modelId());
        ThinkingLevel thinking = request.thinkingLevel().orElseGet(() ->
            parentContext.thinkingLevel() == null ? parentModel.thinkingLevel() : parentContext.thinkingLevel()
        );
        ModelSelection selection = new ModelSelection(provider, model, thinking);
        if (request.provider().isPresent() || request.model().isPresent() || request.thinkingLevel().isPresent()) {
            if (modelCatalog == null) {
                throw new IllegalArgumentException("Model catalog is unavailable for explicit subagent configuration");
            }
            ModelDescriptor descriptor = modelCatalog.find(selection)
                .orElseThrow(() -> new IllegalArgumentException("Unknown subagent model: " + provider + "/" + model));
            if (thinking != ThinkingLevel.OFF && !descriptor.supportsThinking()) {
                throw new IllegalArgumentException("Model does not support thinking: " + provider + "/" + model);
            }
        }
        return selection;
    }

    private PermissionRuntimeState childPermissions(PermissionRuntimeState parent) {
        PermissionRuntimeState auto = PermissionRuntimeState.forMode(PermissionMode.AUTO);
        return new PermissionRuntimeState(
            ApprovalPolicy.forMode(PermissionMode.AUTO),
            parent.activePermissionProfile(),
            parent.permissionProfile(),
            auto.legacyBehavior(),
            PermissionMode.AUTO
        );
    }

    private void validateRequest(SubagentSpawnRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Subagent spawn request is required");
        }
        if (!parentSession.currentView().sessionId().equals(request.parentSessionId())) {
            throw new IllegalArgumentException("Parent session does not match current session");
        }
        if (safe(request.taskName()).isBlank()) {
            throw new IllegalArgumentException("taskName is required");
        }
        if (safe(request.message()).isBlank()) {
            throw new IllegalArgumentException("message is required");
        }
    }

    private String resolveParentEntryId(String requested) {
        return safe(requested).isBlank() ? parentSession.currentView().leafId() : requested;
    }

    private HeadlessSubagentOutput failedOutput(RunningSubagentRun running, Throwable failure) {
        return new HeadlessSubagentOutput(
            running.agent().taskName(),
            running.agent().agentId(),
            running.agent().childSessionId(),
            running.runId(),
            SubagentRunStatus.FAILED,
            "",
            Optional.empty(),
            Optional.ofNullable(failure == null ? "Subagent failed" : failure.getMessage())
        );
    }

    private SubagentSpawnResult failedSpawn(String taskName, String message) {
        return new SubagentSpawnResult(
            taskName,
            "",
            "",
            "",
            SubagentRunStatus.FAILED,
            Optional.ofNullable(message)
        );
    }

    private SubagentSpawnResult failedSpawn(String taskName, SubagentAgent agent, String runId, String message) {
        return new SubagentSpawnResult(
            taskName,
            agent.agentId(),
            agent.childSessionId(),
            runId,
            SubagentRunStatus.FAILED,
            Optional.ofNullable(message)
        );
    }

    private String randomId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String subagentCommandMissingMessage() {
        return "Subagent command is not configured. Configure lypi.subagent.command or run from a packaged lypi-boot jar.";
    }
}
