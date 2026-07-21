---
name: lypi-subagent-runtime
description: Use when changing or debugging ly-pi subagent spawning, waiting, headless JSON protocol, child sessions, AgentCenter, mailbox delivery, or subagent tools.
---

# ly-pi Subagent Runtime

## Core Rule

The model-visible subagent surface contains exactly `spawn_agent` and `wait_agent`. A spawn creates a prompt-only child session and starts one run through the headless process boundary. Completion is delivered exactly once through either `wait_agent` or an `AGENT_COMMUNICATION` steering message.

## Public Tool Surface

`spawn_agent` accepts only:

- required `task_name`: readable task label;
- required `message`: the only user prompt copied into the child turn;
- optional `tools`: canonical tool names appended to the fixed base set;
- optional `provider`, `model`, and `thinking_level`; omit them unless an explicit override is needed, because each omitted or blank field inherits from the parent Agent.

`SubagentToolInputs` trims these three optional model overrides. A missing or blank string becomes `Optional.empty()`; a non-blank string becomes an explicit trimmed override. Non-string values and invalid explicit values remain errors. This is tolerance at the tool input boundary, not a provider schema requirement to populate optional fields; callers should still omit unused overrides.

`wait_agent` accepts only optional `timeout_ms`. It waits for a mailbox completion, user steering, explicit turn abort, or timeout. The runtime records a distinct `COMPLETED`, `STEERED`, `ABORTED`, or `TIMED_OUT` outcome, and the tool renders each case separately. A completion includes content plus `taskName`, `agentId`, `childSessionId`, `runId`, and status.

Do not add compatibility aliases or expose cwd, permission, Agent mode, run selection, mailbox commands, result readers, interrupt, or list operations as model tools.

## Model Scheduling Guidance

The product model sees the scheduling policy in `base-agent-instructions`, both public tool descriptions, and the successful `spawn_agent` result. This repository Codex Skill records that policy for development but is not itself a product runtime prompt source.

- After spawning, the parent should continue useful independent work. Completion is delivered automatically at a later model boundary.
- Call `wait_agent` only when the next step depends on the completion and no useful independent work remains.
- If the user asks not to wait or asks the parent to continue working, do not call `wait_agent`.
- Automatic delivery does not start a new model turn after the current turn ends. A late completion remains pending until the next turn's first model boundary.

## Boundaries

- `lypi-tool` owns the two public schemas and canonical tool-name validation.
- `lypi-runtime` owns `DefaultAgentCenter`, Agent/Run state, process launch, mailbox persistence, wait, and communication polling.
- `lypi-session` creates a fresh child session without copying the parent transcript.
- `lypi-transport-headless` owns the stdin/stdout JSON protocol and executes the child turn.
- `lypi-agent-core` carries the current turn's abort and steering sources through `ToolRuntimeInvocation`, consumes completion through `AgentCommunicationPort`, and must not import runtime, tool, or TUI implementations.
- `lypi-transport-tui` owns the active-turn queues and subscription notifications; it does not decide wait outcomes.
- `lypi-boot` wires the headless command, model catalog, mailbox communication port, and filtered child tool runtime.

## Identities

- `taskName` is the readable task label supplied as `task_name`.
- `agentId` is the stable logical Agent identity.
- `childSessionId` identifies the independent child session.
- `runId` identifies one execution of that Agent.

Agent state and running process state are separate (`SubagentAgent` and `RunningSubagentRun`). The current public API starts one run per Agent, but the runtime must not collapse Agent and Run identity because later revisions may run the same Agent again.

`parentSpawnEntryId` is the real parent assistant message entry containing the `spawn_agent` call. It is shared by the child header, live Run snapshot, and mailbox message; there is no synthetic lifecycle entry.

The internal spawn request must provide it explicitly; runtime must reject a missing value instead of falling back to the mutable current leaf.

## Spawn Flow

1. `SpawnAgentTool` rejects unknown input fields and normalizes the requested tools and optional model overrides.
2. `DefaultAgentCenter.spawn()` resolves the parent context at the current parent entry.
3. Omitted or blank provider, model, and thinking level are inherited field by field from the parent Agent.
4. Any explicit model configuration is validated through `ModelCatalogPort` before a child session is created.
5. Runtime creates distinct `agentId`, `childSessionId`, and `runId`, and keeps the real spawn call entry as `parentSpawnEntryId`.
6. `ChildSessionService.create()` writes a new child header and session-info entry only; it does not copy parent messages.
7. Child cwd and session storage cwd are inherited internally from the parent runtime cwd.
8. `JsonSubagentProcessRunner` sends one `HeadlessSubagentInput`; `HeadlessSubagentRunner` executes one `TurnRequest` containing only `message`.
9. Completion publishes one `PENDING` mailbox message and does not append to or move the parent session tree.

Headless stdout must remain one structured JSON value. Diagnostics belong on stderr.

## Child Tools

`read`, `grep`, and `glob` are always present. `tools` adds to that base set.

`SubagentToolPolicyNormalizer` preserves order, removes duplicates, resolves every effective name through the current `ToolRuntimePort`, and rejects aliases by requiring the requested name to equal `Tool.name()`. `FilteredToolRuntime` enforces the resulting effective set again inside the child runtime.

## Child Permissions

The child approval mode and approval policy are always `AUTO`. It inherits the parent session's active sandbox/profile selection and permission profile, but it does not inherit the parent's approval mode.

Turn-scoped additional permissions and `strictAutoReview` are not copied into the child header, headless input, or child tool runtime. No permission parameter is exposed by `spawn_agent`.

## Completion Delivery

`DefaultMailboxService.waitAndConsume()` and `AgentCommunicationPort.poll()` share one synchronized `consumePending()` transition. The first consumer changes the mailbox message from `PENDING` to `DELIVERED`; the other path cannot inject it again.

- While the parent turn is active, `DefaultTurnExecutor` polls at model boundaries and persists the completion as `MessageRole.SYSTEM_LOCAL` with steering type `AGENT_COMMUNICATION`.
- While the parent is inside `wait_agent`, the wait tool consumes the message and returns it directly in the tool result.
- After the parent turn ends, the `PENDING` message remains persisted and is polled at the next turn's first model boundary.

Agent communication must never be represented as a user message.

Asynchronous completion must not change the parent session leaf. In particular, completion arriving between a `wait_agent` call and its result must leave both the spawn and wait call/result pairs reachable in the next model context.

## Wait Activity

`DefaultMailboxService.waitAndConsume()` uses signal subscriptions rather than interval polling. Its outcome priority is abort, user steering, mailbox completion, then timeout.

- `COMPLETED` consumes exactly one `PENDING` mailbox message.
- `STEERED` wakes the wait without polling the steering source or consuming a mailbox message. `DefaultTurnExecutor` polls and persists the user message at the same turn's next model boundary.
- `ABORTED` wakes the wait, lets the wait tool result be persisted, and then ends the turn before steering consumption or another model call.
- `TIMED_OUT` leaves child execution and pending mailbox state unchanged.

The TUI notifies subscribers after active-turn queue or abort state changes. Listener notification must occur outside `activeTurnLock`; runtime remains responsible for priority and mailbox consumption.

## Internal-Only Surfaces

`AgentCenterPort.interrupt()` remains for the TUI `/agent interrupt` command and process cleanup. `DefaultAgentRegistry` remains available to TUI and compact-state projection. Neither capability is a model tool.

There is no public continue, read-result, mailbox read/accept/stash/discard, interrupt, or list tool. Compatibility enum values or persisted fields that remain in internal contracts do not make those operations public.

## Key Code

- `lypi-tool/src/main/java/cn/lypi/tool/builtin/subagent/SpawnAgentTool.java`
- `lypi-tool/src/main/java/cn/lypi/tool/builtin/subagent/WaitAgentTool.java`
- `lypi-tool/src/main/java/cn/lypi/tool/builtin/subagent/SubagentToolPolicyNormalizer.java`
- `lypi-agent-core/src/main/java/cn/lypi/agent/DefaultTurnExecutor.java`
- `lypi-contracts/src/main/java/cn/lypi/contracts/common/SignalSubscription.java`
- `lypi-contracts/src/main/java/cn/lypi/contracts/common/AbortSignal.java`
- `lypi-contracts/src/main/java/cn/lypi/contracts/agent/SteeringMessageSource.java`
- `lypi-contracts/src/main/java/cn/lypi/contracts/runtime/ToolRuntimeInvocation.java`
- `lypi-contracts/src/main/java/cn/lypi/contracts/subagent/SubagentWaitRequest.java`
- `lypi-contracts/src/main/java/cn/lypi/contracts/subagent/SubagentWaitOutcome.java`
- `lypi-contracts/src/main/java/cn/lypi/contracts/subagent/SubagentWaitResult.java`
- `lypi-tool/src/main/java/cn/lypi/tool/ToolRuntimeContextFactory.java`
- `lypi-tool/src/main/java/cn/lypi/tool/ToolSteeringSupport.java`
- `lypi-runtime/src/main/java/cn/lypi/runtime/subagent/DefaultAgentCenter.java`
- `lypi-runtime/src/main/java/cn/lypi/runtime/subagent/DefaultMailboxService.java`
- `lypi-runtime/src/main/java/cn/lypi/runtime/subagent/SubagentAgent.java`
- `lypi-runtime/src/main/java/cn/lypi/runtime/subagent/RunningSubagentRun.java`
- `lypi-session/src/main/java/cn/lypi/session/ChildSessionService.java`
- `lypi-transport-headless/src/main/java/cn/lypi/transport/headless/HeadlessSubagentRunner.java`
- `lypi-transport-tui/src/main/java/cn/lypi/transport/tui/MutableAbortSignal.java`
- `lypi-transport-tui/src/main/java/cn/lypi/transport/tui/RuntimeTuiSubmitHandler.java`
- `lypi-contracts/src/main/java/cn/lypi/contracts/runtime/AgentCommunicationPort.java`
- `lypi-contracts/src/main/java/cn/lypi/contracts/subagent/HeadlessSubagentInput.java`

## Tests To Check

- `lypi-tool/src/test/java/cn/lypi/tool/builtin/subagent/SubagentToolsTest.java`
- `lypi-tool/src/test/java/cn/lypi/tool/ToolRuntimeContextFactoryTest.java`
- `lypi-runtime/src/test/java/cn/lypi/runtime/subagent/DefaultAgentCenterTest.java`
- `lypi-runtime/src/test/java/cn/lypi/runtime/subagent/DefaultMailboxServiceTest.java`
- `lypi-runtime/src/test/java/cn/lypi/runtime/subagent/JsonlMailboxStoreTest.java`
- `lypi-agent-core/src/test/java/cn/lypi/agent/DefaultTurnExecutorTest.java`
- `lypi-contracts/src/test/java/cn/lypi/contracts/ContractSerializationTest.java`
- `lypi-transport-tui/src/test/java/cn/lypi/transport/tui/RuntimeTuiSubmitHandlerTest.java`
- `lypi-session/src/test/java/cn/lypi/session/ChildSessionServiceTest.java`
- `lypi-transport-headless/src/test/java/cn/lypi/transport/headless/HeadlessSubagentRunnerTest.java`
- `lypi-transport-headless/src/test/java/cn/lypi/transport/headless/HeadlessSubagentJsonCodecTest.java`
- `lypi-boot/src/test/java/cn/lypi/boot/SubagentRuntimeEndToEndTest.java`

## Before Editing

- Check whether a change affects the public schema, identities, child context, permissions, process protocol, or completion ownership.
- Keep wait and agent communication polling on the same atomic mailbox consumer; user steering is a non-consuming wake signal.
- Test timeout, completion, steering, and abort paths, plus the active-turn, waiting-turn, and next-turn delivery timings.
- For tool changes, test base tools, additions, duplicates, aliases, and missing names.
- For model changes, test omitted and blank inheritance, trimmed explicit overrides, invalid input types, and explicit runtime validation.
- Reverse-check every source and test path listed above against current code before updating this Skill.
