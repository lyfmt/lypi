package cn.lypi.runtime.subagent;

import cn.lypi.contracts.runtime.AgentCenterPort;
import cn.lypi.contracts.runtime.ChildSessionPort;
import cn.lypi.contracts.runtime.SessionManagerFactoryPort;
import cn.lypi.contracts.runtime.SessionManagerPort;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.session.AgentLifecycleEntry;
import cn.lypi.contracts.session.ChildSessionRequest;
import cn.lypi.contracts.session.CustomEntry;
import cn.lypi.contracts.session.ModeChangeEntry;
import cn.lypi.contracts.session.ModelChangeEntry;
import cn.lypi.contracts.session.PermissionModeChangeEntry;
import cn.lypi.contracts.session.ThinkingChangeEntry;
import cn.lypi.contracts.subagent.HeadlessSubagentInput;
import cn.lypi.contracts.subagent.HeadlessSubagentOutput;
import cn.lypi.contracts.subagent.HeadlessSubagentRunMode;
import cn.lypi.contracts.subagent.MailboxCommandResult;
import cn.lypi.contracts.subagent.MailboxMessage;
import cn.lypi.contracts.subagent.MailboxStatus;
import cn.lypi.contracts.subagent.SubagentResultRef;
import cn.lypi.contracts.subagent.SubagentRunStatus;
import cn.lypi.contracts.subagent.SubagentContinueRequest;
import cn.lypi.contracts.subagent.SubagentContinueResult;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class DefaultAgentCenter implements AgentCenterPort, RunningAgentSnapshotProvider {
    private final List<String> command;
    private final ChildSessionPort childSessions;
    private final SessionManagerPort parentSession;
    private final Path parentCwd;
    private final SessionManagerFactoryPort sessionManagerFactory;
    private final SubagentProcessRunner processRunner;
    private final DefaultMailboxService mailbox;
    private final MailboxDeliveryService deliveryService;
    private final Clock clock;
    private final Map<String, RunningAgent> runningByAgentId = new ConcurrentHashMap<>();
    private final Map<String, RunningAgent> agentsByChildSessionId = new ConcurrentHashMap<>();
    private final Map<String, String> agentIdByChildSessionId = new ConcurrentHashMap<>();
    private final Map<String, HeadlessSubagentOutput> resultsByChildSessionId = new ConcurrentHashMap<>();
    private final Map<String, String> latestRunIdByChildSessionId = new ConcurrentHashMap<>();

    public DefaultAgentCenter(
        List<String> command,
        ChildSessionPort childSessions,
        SessionManagerPort parentSession,
        Path parentCwd,
        SessionManagerFactoryPort sessionManagerFactory,
        SubagentProcessRunner processRunner,
        DefaultMailboxService mailbox,
        MailboxDeliveryService deliveryService,
        Clock clock
    ) {
        this.command = command == null ? List.of() : List.copyOf(command);
        this.childSessions = childSessions;
        this.parentSession = parentSession;
        this.parentCwd = parentCwd;
        this.sessionManagerFactory = sessionManagerFactory;
        this.processRunner = processRunner;
        this.mailbox = mailbox;
        this.deliveryService = deliveryService;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @Override
    public SubagentSpawnResult spawn(SubagentSpawnRequest request) {
        if (command.isEmpty()) {
            return failedSpawn(request, "Subagent command is not configured");
        }
        String agentId = "agent_" + randomId();
        String childSessionId = "ses_child_" + randomId();
        String parentSpawnEntryId = "entry_spawn_" + randomId();
        Instant now = Instant.now(clock);
        SubagentToolPolicy toolPolicy = request.toolPolicy();
        childSessions.create(new ChildSessionRequest(
            childSessionId,
            request.parentSessionId(),
            parentSpawnEntryId,
            parentCwd,
            request.cwd(),
            1,
            request.agentName(),
            request.agentRole(),
            request.model(),
            request.thinkingLevel(),
            request.agentMode(),
            request.permissionModeSpecified() ? Optional.of(request.permissionMode()) : Optional.empty(),
            toolPolicy
        ));
        parentSession.append(new AgentLifecycleEntry(
            parentSpawnEntryId,
            request.parentEntryId(),
            agentId,
            childSessionId,
            request.parentSessionId(),
            "spawned",
            Map.of(
                "command", command,
                "prompt", request.prompt()
            ),
            now
        ));
        HeadlessSubagentInput input = new HeadlessSubagentInput(
            childSessionId,
            request.parentSessionId(),
            parentSpawnEntryId,
            request.prompt(),
            parentCwd,
            request.cwd(),
            request.allowedTools(),
            toolPolicy,
            request.permissionMode(),
            request.timeoutSeconds(),
            null
        );
        SubagentProcessHandle handle;
        try {
            handle = processRunner.start(input);
        } catch (RuntimeException exception) {
            HeadlessSubagentOutput output = new HeadlessSubagentOutput(
                childSessionId,
                SubagentRunStatus.FAILED,
                "",
                Optional.empty(),
                Optional.ofNullable(exception.getMessage())
            );
            completeStartedAgent(
                new RunningAgent(
                    agentId,
                    childSessionId,
                    request.parentSessionId(),
                    parentSpawnEntryId,
                    request.agentName(),
                    request.agentRole(),
                    parentCwd,
                    null
                ),
                output
            );
            return new SubagentSpawnResult(
                agentId,
                childSessionId,
                request.parentSessionId(),
                parentSpawnEntryId,
                SubagentRunStatus.FAILED,
                Optional.ofNullable(exception.getMessage())
            );
        }
        RunningAgent running = new RunningAgent(
            agentId,
            childSessionId,
            request.parentSessionId(),
            parentSpawnEntryId,
            request.agentName(),
            request.agentRole(),
            parentCwd,
            handle
        );
        runningByAgentId.put(agentId, running);
        agentsByChildSessionId.put(childSessionId, running);
        agentIdByChildSessionId.put(childSessionId, agentId);
        latestRunIdByChildSessionId.put(childSessionId, parentSpawnEntryId);
        handle.completion().whenComplete((output, failure) -> complete(agentId, output, failure));
        return new SubagentSpawnResult(
            agentId,
            childSessionId,
            request.parentSessionId(),
            parentSpawnEntryId,
            SubagentRunStatus.STARTED,
            Optional.of("subagent started")
        );
    }

    @Override
    public SubagentContinueResult continueRun(SubagentContinueRequest request) {
        RunningAgent existing = agentsByChildSessionId.get(request.childSessionId());
        if (existing == null) {
            return failedContinue(request, "Unknown child session: " + request.childSessionId());
        }
        if (runningByAgentId.containsKey(existing.agentId())) {
            return failedContinue(request, "Subagent is already running: " + existing.agentId());
        }
        String parentContinueEntryId = "entry_continue_" + randomId();
        RunningAgent running = existing.withParentSpawnEntryId(parentContinueEntryId).withHandle(null);
        applyContinueContextChanges(existing, request);
        parentSession.append(new AgentLifecycleEntry(
            parentContinueEntryId,
            request.parentEntryId(),
            existing.agentId(),
            existing.childSessionId(),
            existing.parentSessionId(),
            "continued",
            Map.of("prompt", request.prompt()),
            Instant.now(clock)
        ));
        HeadlessSubagentInput input = new HeadlessSubagentInput(
            existing.childSessionId(),
            existing.parentSessionId(),
            parentContinueEntryId,
            request.prompt(),
            existing.parentCwd(),
            request.cwd() == null ? parentCwd : request.cwd(),
            request.allowedTools(),
            request.toolPolicy(),
            request.permissionMode(),
            request.timeoutSeconds(),
            HeadlessSubagentRunMode.CONTINUE
        );
        try {
            SubagentProcessHandle handle = processRunner.start(input);
            running = running.withHandle(handle);
            runningByAgentId.put(existing.agentId(), running);
            agentsByChildSessionId.put(existing.childSessionId(), running);
            latestRunIdByChildSessionId.put(existing.childSessionId(), parentContinueEntryId);
            handle.completion().whenComplete((output, failure) -> complete(existing.agentId(), output, failure));
            return new SubagentContinueResult(
                existing.agentId(),
                existing.childSessionId(),
                existing.parentSessionId(),
                parentContinueEntryId,
                parentContinueEntryId,
                SubagentRunStatus.STARTED,
                Optional.of("subagent continued")
            );
        } catch (RuntimeException exception) {
            return failedContinue(request, exception.getMessage());
        }
    }

    private void applyContinueContextChanges(RunningAgent existing, SubagentContinueRequest request) {
        if (request.model().isEmpty()
            && request.thinkingLevel().isEmpty()
            && request.agentMode().isEmpty()
            && request.permissionMode() == PermissionMode.DEFAULT_EXECUTE) {
            return;
        }
        SessionManagerPort childSession = sessionManagerFactory.open(existing.parentCwd(), existing.childSessionId());
        String parentId = childSession.currentView().leafId();
        Instant now = Instant.now(clock);
        if (request.model().isPresent()) {
            String entryId = "entry_model_" + randomId();
            childSession.append(new ModelChangeEntry(entryId, parentId, request.model().orElseThrow(), "subagent continue model", now));
            parentId = entryId;
        }
        if (request.thinkingLevel().isPresent()) {
            String entryId = "entry_thinking_" + randomId();
            childSession.append(new ThinkingChangeEntry(entryId, parentId, request.thinkingLevel().orElseThrow(), "subagent continue thinking", now));
            parentId = entryId;
        }
        if (request.agentMode().isPresent()) {
            String entryId = "entry_mode_" + randomId();
            childSession.append(new ModeChangeEntry(entryId, parentId, request.agentMode().orElseThrow(), "subagent continue mode", now));
            parentId = entryId;
        }
        if (request.permissionMode() != PermissionMode.DEFAULT_EXECUTE) {
            String entryId = "entry_permission_" + randomId();
            childSession.append(new PermissionModeChangeEntry(entryId, parentId, request.permissionMode(), "subagent continue permission", now));
        }
    }

    @Override
    public SubagentWaitResult waitFor(SubagentWaitRequest request) {
        RunningAgent running = findRunning(request);
        if (running == null) {
            return completedWaitResult(request);
        }
        try {
            HeadlessSubagentOutput output = running.handle()
                .completion()
                .get(Math.max(0, request.timeoutSeconds()), TimeUnit.SECONDS);
            return waitResult(running.agentId(), running.parentSpawnEntryId(), output);
        } catch (TimeoutException exception) {
            return new SubagentWaitResult(
                running.agentId(),
                running.childSessionId(),
                running.parentSpawnEntryId(),
                SubagentRunStatus.TIMED_OUT,
                Optional.empty(),
                Optional.empty(),
                Optional.empty()
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new SubagentWaitResult(
                running.agentId(),
                running.childSessionId(),
                running.parentSpawnEntryId(),
                SubagentRunStatus.INTERRUPTED,
                Optional.empty(),
                Optional.empty(),
                Optional.of("Interrupted while waiting for subagent")
            );
        } catch (Exception exception) {
            return new SubagentWaitResult(
                running.agentId(),
                running.childSessionId(),
                running.parentSpawnEntryId(),
                SubagentRunStatus.FAILED,
                Optional.empty(),
                Optional.empty(),
                Optional.ofNullable(exception.getMessage())
            );
        }
    }

    @Override
    public MailboxCommandResult interrupt(String agentId) {
        RunningAgent running = runningByAgentId.get(agentId);
        if (running == null) {
            return MailboxCommandResult.failure("Agent is not running: " + agentId);
        }
        try {
            appendInterruptFact(running);
        } catch (RuntimeException exception) {
            return MailboxCommandResult.failure("Failed to persist interrupt command: " + exception.getMessage());
        }
        running.handle().interrupt();
        return MailboxCommandResult.success(null);
    }

    @Override
    public Optional<HeadlessSubagentOutput> readResult(String childSessionId) {
        HeadlessSubagentOutput result = resultsByChildSessionId.get(childSessionId);
        if (result != null) {
            return Optional.of(result);
        }
        return mailbox.readResult(childSessionId);
    }

    @Override
    public List<RunningAgentSnapshot> runningAgents(String parentSessionId) {
        if (parentSessionId == null || parentSessionId.isBlank()) {
            return List.of();
        }
        return runningByAgentId.values().stream()
            .filter(running -> parentSessionId.equals(running.parentSessionId()))
            .map(running -> new RunningAgentSnapshot(
                running.agentId(),
                running.childSessionId(),
                running.parentSessionId(),
                running.parentSpawnEntryId(),
                running.agentName(),
                running.agentRole()
            ))
            .toList();
    }

    private void complete(String agentId, HeadlessSubagentOutput output, Throwable failure) {
        RunningAgent running = runningByAgentId.remove(agentId);
        if (running == null) {
            return;
        }
        HeadlessSubagentOutput safeOutput = output == null
            ? failedOutput(running.childSessionId(), failure)
            : output;
        completeStartedAgent(running, safeOutput);
    }

    private void completeStartedAgent(RunningAgent running, HeadlessSubagentOutput safeOutput) {
        resultsByChildSessionId.put(running.childSessionId(), safeOutput);
        agentsByChildSessionId.put(running.childSessionId(), running.withHandle(null));
        agentIdByChildSessionId.put(running.childSessionId(), running.agentId());
        latestRunIdByChildSessionId.put(running.childSessionId(), running.parentSpawnEntryId());
        SessionManagerPort lifecycleSession = sessionManagerFactory.open(running.parentCwd(), running.parentSessionId());
        lifecycleSession.append(new AgentLifecycleEntry(
            "entry_agent_" + randomId(),
            running.parentSpawnEntryId(),
            running.agentId(),
            running.childSessionId(),
            running.parentSessionId(),
            lifecycle(safeOutput.status()),
            Map.of(
                "status", safeOutput.status().name(),
                "errorMessage", safeOutput.errorMessage().orElse("")
            ),
            Instant.now(clock)
        ));
        MailboxMessage message = mailboxMessage(running, safeOutput);
        mailbox.publish(message);
        deliveryService.tryDeliver(message);
    }

    private RunningAgent findRunning(SubagentWaitRequest request) {
        Optional<String> agentId = request.agentId();
        if (agentId.isPresent()) {
            return runningByAgentId.get(agentId.get());
        }
        return request.childSessionId()
            .flatMap(childSessionId -> runningByAgentId.values().stream()
                .filter(running -> childSessionId.equals(running.childSessionId()))
                .findFirst())
            .orElse(null);
    }

    private SubagentWaitResult completedWaitResult(SubagentWaitRequest request) {
        Optional<String> requestedChildSessionId = request.childSessionId();
        Optional<String> resolvedChildSessionId = requestedChildSessionId;
        if (resolvedChildSessionId.isEmpty() && request.agentId().isPresent()) {
            resolvedChildSessionId = agentIdByChildSessionId.entrySet().stream()
                .filter(entry -> request.agentId().get().equals(entry.getValue()))
                .map(Map.Entry::getKey)
                .findFirst();
        }
        if (resolvedChildSessionId.isEmpty()) {
            return failedWaitResult(request, "Subagent is not running and no completed result was found");
        }
        String childSessionId = resolvedChildSessionId.get();
        Optional<HeadlessSubagentOutput> output = readResult(childSessionId);
        if (output.isEmpty()) {
            return failedWaitResult(request, "Subagent is not running and no completed result was found");
        }
        return waitResult(
            request.agentId().orElseGet(() -> agentIdByChildSessionId.getOrDefault(childSessionId, "")),
            request.runId().orElseGet(() -> latestRunIdByChildSessionId.getOrDefault(childSessionId, "")),
            output.get()
        );
    }

    private SubagentWaitResult failedWaitResult(SubagentWaitRequest request, String message) {
        return new SubagentWaitResult(
            request.agentId().orElse(""),
            request.childSessionId().orElse(""),
            request.runId().orElse(""),
            SubagentRunStatus.FAILED,
            Optional.empty(),
            Optional.empty(),
            Optional.of(message)
        );
    }

    private SubagentWaitResult waitResult(String agentId, String runId, HeadlessSubagentOutput output) {
        return new SubagentWaitResult(
            agentId,
            output.childSessionId(),
            runId,
            output.status(),
            optionalNonBlank(output.summary()),
            output.finalEntryId(),
            output.errorMessage()
        );
    }

    private Optional<String> optionalNonBlank(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    private HeadlessSubagentOutput failedOutput(String childSessionId, Throwable failure) {
        return new HeadlessSubagentOutput(
            childSessionId,
            SubagentRunStatus.FAILED,
            "",
            Optional.empty(),
            Optional.ofNullable(failure == null ? "Subagent failed" : failure.getMessage())
        );
    }

    private void appendInterruptFact(RunningAgent running) {
        parentSession.append(new CustomEntry(
            "entry_agent_command_" + randomId(),
            parentSession.currentView().leafId(),
            "agent_command",
            Map.of(
                "action", "interrupt",
                "agentId", running.agentId(),
                "childSessionId", running.childSessionId(),
                "parentSessionId", running.parentSessionId(),
                "parentSpawnEntryId", running.parentSpawnEntryId()
            ),
            Instant.now(clock)
        ));
    }

    private MailboxMessage mailboxMessage(RunningAgent running, HeadlessSubagentOutput output) {
        Instant now = Instant.now(clock);
        return new MailboxMessage(
            "mail_" + randomId(),
            running.agentId(),
            running.childSessionId(),
            running.parentSessionId(),
            running.parentSpawnEntryId(),
            mailboxSummary(output),
            new SubagentResultRef(
                running.childSessionId(),
                output.finalEntryId().orElse(""),
                Optional.empty(),
                Optional.of(output.status())
            ),
            MailboxStatus.PENDING,
            now,
            now
        );
    }

    private String mailboxSummary(HeadlessSubagentOutput output) {
        if (output.summary() != null && !output.summary().isBlank()) {
            return output.summary();
        }
        return output.errorMessage()
            .filter(message -> !message.isBlank())
            .orElse(output.status().name());
    }

    private SubagentSpawnResult failedSpawn(SubagentSpawnRequest request, String message) {
        return new SubagentSpawnResult(
            "",
            "",
            request.parentSessionId(),
            "",
            SubagentRunStatus.FAILED,
            Optional.of(message)
        );
    }

    private SubagentContinueResult failedContinue(SubagentContinueRequest request, String message) {
        return new SubagentContinueResult(
            "",
            request.childSessionId(),
            request.parentSessionId(),
            "",
            "",
            SubagentRunStatus.FAILED,
            Optional.ofNullable(message)
        );
    }

    private String lifecycle(SubagentRunStatus status) {
        return switch (status) {
            case SUCCEEDED -> "finished";
            case INTERRUPTED -> "interrupted";
            case TIMED_OUT -> "timed_out";
            case STARTED, RUNNING, FAILED -> "failed";
        };
    }

    private String randomId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private record RunningAgent(
        String agentId,
        String childSessionId,
        String parentSessionId,
        String parentSpawnEntryId,
        Optional<String> agentName,
        Optional<String> agentRole,
        Path parentCwd,
        SubagentProcessHandle handle
    ) {
        private RunningAgent withHandle(SubagentProcessHandle handle) {
            return new RunningAgent(
                agentId,
                childSessionId,
                parentSessionId,
                parentSpawnEntryId,
                agentName,
                agentRole,
                parentCwd,
                handle
            );
        }

        private RunningAgent withParentSpawnEntryId(String parentSpawnEntryId) {
            return new RunningAgent(
                agentId,
                childSessionId,
                parentSessionId,
                parentSpawnEntryId,
                agentName,
                agentRole,
                parentCwd,
                handle
            );
        }
    }
}
