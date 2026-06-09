package cn.lypi.runtime.subagent;

import cn.lypi.contracts.runtime.AgentCenterPort;
import cn.lypi.contracts.runtime.ChildSessionPort;
import cn.lypi.contracts.runtime.SessionManagerFactoryPort;
import cn.lypi.contracts.runtime.SessionManagerPort;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.session.AgentLifecycleEntry;
import cn.lypi.contracts.session.ChildSessionRequest;
import cn.lypi.contracts.session.CustomEntry;
import cn.lypi.contracts.subagent.HeadlessSubagentInput;
import cn.lypi.contracts.subagent.HeadlessSubagentOutput;
import cn.lypi.contracts.subagent.MailboxCommandResult;
import cn.lypi.contracts.subagent.MailboxMessage;
import cn.lypi.contracts.subagent.MailboxStatus;
import cn.lypi.contracts.subagent.SubagentResultRef;
import cn.lypi.contracts.subagent.SubagentRunStatus;
import cn.lypi.contracts.subagent.SubagentSpawnRequest;
import cn.lypi.contracts.subagent.SubagentSpawnResult;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
    private final Map<String, HeadlessSubagentOutput> resultsByChildSessionId = new ConcurrentHashMap<>();

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
        if (!request.allowedTools().isEmpty() || request.permissionMode() != PermissionMode.DEFAULT_EXECUTE) {
            return failedSpawn(request, "Subagent tool and permission isolation is not implemented");
        }
        String agentId = "agent_" + randomId();
        String childSessionId = "ses_child_" + randomId();
        String parentSpawnEntryId = "entry_spawn_" + randomId();
        Instant now = Instant.now(clock);
        childSessions.create(new ChildSessionRequest(
            childSessionId,
            request.parentSessionId(),
            parentSpawnEntryId,
            parentCwd,
            request.cwd(),
            1,
            request.agentName(),
            request.agentRole()
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
            request.permissionMode(),
            request.timeoutSeconds()
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
        runningByAgentId.put(
            agentId,
            new RunningAgent(
                agentId,
                childSessionId,
                request.parentSessionId(),
                parentSpawnEntryId,
                request.agentName(),
                request.agentRole(),
                parentCwd,
                handle
            )
        );
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
    ) {}
}
