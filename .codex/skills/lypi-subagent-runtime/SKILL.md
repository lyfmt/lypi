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
- optional `provider`, `model`, and `thinking_level`.

`wait_agent` accepts only optional `timeout_ms`. It waits for any pending completion in the current parent session, returns completion content plus `taskName`, `agentId`, `childSessionId`, `runId`, and status, or reports that no subagent replied before the timeout.

Do not add compatibility aliases or expose cwd, permission, Agent mode, run selection, mailbox commands, result readers, interrupt, or list operations as model tools.

## Boundaries

- `lypi-tool` owns the two public schemas and canonical tool-name validation.
- `lypi-runtime` owns `DefaultAgentCenter`, Agent/Run state, process launch, mailbox persistence, wait, and communication polling.
- `lypi-session` creates a fresh child session without copying the parent transcript.
- `lypi-transport-headless` owns the stdin/stdout JSON protocol and executes the child turn.
- `lypi-agent-core` consumes completion through `AgentCommunicationPort`; it must not import runtime, tool, or TUI implementations.
- `lypi-boot` wires the headless command, model catalog, mailbox communication port, and filtered child tool runtime.

## Identities

- `taskName` is the readable task label supplied as `task_name`.
- `agentId` is the stable logical Agent identity.
- `childSessionId` identifies the independent child session.
- `runId` identifies one execution of that Agent.

Agent state and running process state are separate (`SubagentAgent` and `RunningSubagentRun`). The current public API starts one run per Agent, but the runtime must not collapse Agent and Run identity because later revisions may run the same Agent again.

## Spawn Flow

1. `SpawnAgentTool` rejects unknown input fields and normalizes the requested tools.
2. `DefaultAgentCenter.spawn()` resolves the parent context at the current parent entry.
3. Omitted provider, model, and thinking level are inherited field by field from the parent Agent.
4. Any explicit model configuration is validated through `ModelCatalogPort` before a child session is created.
5. Runtime creates distinct `agentId`, `childSessionId`, `runId`, and lifecycle entry IDs.
6. `ChildSessionService.create()` writes a new child header and session-info entry only; it does not copy parent messages.
7. Child cwd and session storage cwd are inherited internally from the parent runtime cwd.
8. `JsonSubagentProcessRunner` sends one `HeadlessSubagentInput`; `HeadlessSubagentRunner` executes one `TurnRequest` containing only `message`.
9. Completion appends a parent lifecycle entry and publishes one `PENDING` mailbox message.

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

## Internal-Only Surfaces

`AgentCenterPort.interrupt()` remains for the TUI `/agent interrupt` command and process cleanup. `DefaultAgentRegistry` remains available to TUI and compact-state projection. Neither capability is a model tool.

There is no public continue, read-result, mailbox read/accept/stash/discard, interrupt, or list tool. Compatibility enum values or persisted fields that remain in internal contracts do not make those operations public.

## Key Code

- `lypi-tool/src/main/java/cn/lypi/tool/builtin/subagent/SpawnAgentTool.java`
- `lypi-tool/src/main/java/cn/lypi/tool/builtin/subagent/WaitAgentTool.java`
- `lypi-tool/src/main/java/cn/lypi/tool/builtin/subagent/SubagentToolPolicyNormalizer.java`
- `lypi-runtime/src/main/java/cn/lypi/runtime/subagent/DefaultAgentCenter.java`
- `lypi-runtime/src/main/java/cn/lypi/runtime/subagent/DefaultMailboxService.java`
- `lypi-runtime/src/main/java/cn/lypi/runtime/subagent/SubagentAgent.java`
- `lypi-runtime/src/main/java/cn/lypi/runtime/subagent/RunningSubagentRun.java`
- `lypi-session/src/main/java/cn/lypi/session/ChildSessionService.java`
- `lypi-transport-headless/src/main/java/cn/lypi/transport/headless/HeadlessSubagentRunner.java`
- `lypi-contracts/src/main/java/cn/lypi/contracts/runtime/AgentCommunicationPort.java`
- `lypi-contracts/src/main/java/cn/lypi/contracts/subagent/HeadlessSubagentInput.java`

## Tests To Check

- `lypi-tool/src/test/java/cn/lypi/tool/builtin/subagent/SubagentToolsTest.java`
- `lypi-runtime/src/test/java/cn/lypi/runtime/subagent/DefaultAgentCenterTest.java`
- `lypi-runtime/src/test/java/cn/lypi/runtime/subagent/DefaultMailboxServiceTest.java`
- `lypi-runtime/src/test/java/cn/lypi/runtime/subagent/JsonlMailboxStoreTest.java`
- `lypi-agent-core/src/test/java/cn/lypi/agent/DefaultTurnExecutorTest.java`
- `lypi-session/src/test/java/cn/lypi/session/ChildSessionServiceTest.java`
- `lypi-transport-headless/src/test/java/cn/lypi/transport/headless/HeadlessSubagentRunnerTest.java`
- `lypi-transport-headless/src/test/java/cn/lypi/transport/headless/HeadlessSubagentJsonCodecTest.java`
- `lypi-boot/src/test/java/cn/lypi/boot/SubagentRuntimeEndToEndTest.java`

## Before Editing

- Check whether a change affects the public schema, identities, child context, permissions, process protocol, or completion ownership.
- Keep wait and steering on the same atomic mailbox consumer.
- Test timeout and completion paths, plus the active-turn, waiting-turn, and next-turn delivery timings.
- For tool changes, test base tools, additions, duplicates, aliases, and missing names.
- For model changes, test inheritance and explicit runtime validation.
- Reverse-check every source and test path listed above against current code before updating this Skill.
