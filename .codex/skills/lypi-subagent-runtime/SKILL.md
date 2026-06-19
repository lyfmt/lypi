---
name: lypi-subagent-runtime
description: Use when changing or debugging ly-pi subagent spawning, waiting, continuing, headless JSON protocol, child sessions, AgentCenter, mailbox delivery, or subagent tools.
---

# ly-pi Subagent Runtime

## Core Rule

Subagents are child sessions executed through a headless process boundary. The parent sees lifecycle, wait/read results and mailbox summaries; it should not assume live access to the child transcript unless it explicitly reads child session data.

## Current Source Of Truth

Current source is authoritative. In particular, current source includes `wait_agent` and `continue_agent`; do not repeat older claims that these tools are absent without rechecking code.

## Boundaries

- `lypi-tool` exposes parent-callable subagent tools.
- `lypi-runtime` owns `DefaultAgentCenter`, process launch, running snapshots, result cache and mailbox.
- `lypi-session` creates child sessions.
- `lypi-transport-headless` owns stdin/stdout JSON protocol.
- `lypi-boot` wires headless startup mode and subagent command config.
- Permission runtime state is canonical `PermissionRuntimeState`; legacy `PermissionMode` is accepted only for compatibility.

## Key Code

- `lypi-tool/src/main/java/cn/lypi/tool/builtin/subagent/SpawnAgentTool.java`
- `lypi-tool/src/main/java/cn/lypi/tool/builtin/subagent/WaitAgentTool.java`
- `lypi-tool/src/main/java/cn/lypi/tool/builtin/subagent/ContinueAgentTool.java`
- `lypi-tool/src/main/java/cn/lypi/tool/builtin/subagent/ReadAgentResultTool.java`
- `lypi-tool/src/main/java/cn/lypi/tool/builtin/subagent/ReadMailboxTool.java`
- `lypi-runtime/src/main/java/cn/lypi/runtime/subagent/DefaultAgentCenter.java`
- `lypi-runtime/src/main/java/cn/lypi/runtime/subagent/JsonSubagentProcessRunner.java`
- `lypi-runtime/src/main/java/cn/lypi/runtime/subagent/DefaultMailboxService.java`
- `lypi-runtime/src/main/java/cn/lypi/runtime/subagent/MailboxDeliveryService.java`
- `lypi-session/src/main/java/cn/lypi/session/ChildSessionService.java`
- `lypi-transport-headless/src/main/java/cn/lypi/transport/headless/HeadlessSubagentRunner.java`
- `lypi-transport-headless/src/main/java/cn/lypi/transport/headless/HeadlessSubagentJsonCodec.java`
- `lypi-contracts/src/main/java/cn/lypi/contracts/subagent/HeadlessSubagentInput.java`
- `lypi-contracts/src/main/java/cn/lypi/contracts/subagent/SubagentSpawnRequest.java`
- `lypi-contracts/src/main/java/cn/lypi/contracts/subagent/SubagentContinueRequest.java`

## Spawn Flow

1. Parent calls `spawn_agent`.
2. `SpawnAgentTool` validates prompt, tool policy, cwd, permission runtime and optional model/thinking/mode fields.
3. `DefaultAgentCenter.spawn()` creates `agentId`, `childSessionId` and spawn lifecycle entry.
4. If spawn request explicitly includes permission runtime state, use it; otherwise inherit parent `SessionContext.permissionRuntimeState()`.
5. `ChildSessionService.create()` creates the child session header with canonical initial permission runtime state.
6. `JsonSubagentProcessRunner.start()` launches configured `lypi.subagent.command`.
7. Parent writes `HeadlessSubagentInput` JSON to child stdin, including `permissionRuntimeState` for the new protocol.
8. Child `HeadlessSubagentRunner` opens the child session and executes one `TurnRequest`.
9. Child writes `HeadlessSubagentOutput` JSON to stdout.
10. Parent caches the result, appends completion lifecycle and publishes mailbox.
11. Parent should use `wait_agent`, then `read_agent_result` or `read_mailbox` as needed.

## Continue Flow

`continue_agent` appends another run to an existing child session. It can apply model, thinking, agent mode and permission runtime changes before launching a `HeadlessSubagentRunMode.CONTINUE` input. If permission runtime is omitted, it inherits the child session's current replayed state. If explicit, `DefaultAgentCenter` appends a `PermissionRuntimeStateChangeEntry` before starting the headless continue run. Use `wait_agent` after continuing.

## Headless Permission Protocol

- `HeadlessSubagentInput` serializes `permissionRuntimeState` for the current protocol and reads legacy `permissionMode` for old callers.
- If both fields are present, canonical `permissionRuntimeState` wins.
- `HeadlessSubagentRunner` opens the child session through `SessionManagerFactoryPort`; the effective context comes from child session replay/header, while JSON still carries the state across the process boundary for compatibility and diagnostics.
- Headless stdout must remain pure JSON for both success and permission-denied failures.

## Invariants

- `lypi.subagent.command` must be configured or inferred; otherwise spawn fails.
- Headless stdout must contain only structured JSON. Diagnostics belong on stderr.
- `spawn_agent` and `continue_agent` return start status, not final work completion.
- `wait_agent` returning `FAILED` means the child run failed; read the result instead of silently redoing the task in the parent.
- Child cwd must stay within the parent tool context cwd.
- Default headless permission behavior can deny interactive ASK operations; tool policy and permission mode need explicit review.
- Spawn inherits session-scoped permission runtime state, but must not inherit parent turn-scoped amendments or `strictAutoReview`.
- Continue without explicit permission runtime uses the child session's current permission runtime state.
- Continue with explicit permission runtime appends `PermissionRuntimeStateChangeEntry`; it does not rewrite earlier child entries.
- Mailbox acceptance writes a summary back to the parent session, not the full child transcript.
- Interrupt is process-level termination, not graceful in-band cancellation.

## Tests To Check

- `lypi-tool/src/test/java/cn/lypi/tool/builtin/subagent/SubagentToolsTest.java`
- `lypi-runtime/src/test/java/cn/lypi/runtime/subagent/DefaultAgentCenterTest.java`
- `lypi-runtime/src/test/java/cn/lypi/runtime/subagent/PermissionRuntimeSubagentEndToEndTest.java`
- `lypi-runtime/src/test/java/cn/lypi/runtime/subagent/DefaultAgentRegistryTest.java`
- `lypi-runtime/src/test/java/cn/lypi/runtime/subagent/JsonSubagentProcessRunnerTest.java`
- `lypi-runtime/src/test/java/cn/lypi/runtime/subagent/JsonlMailboxStoreTest.java`
- `lypi-runtime/src/test/java/cn/lypi/runtime/subagent/MailboxDeliveryServiceTest.java`
- `lypi-transport-headless/src/test/java/cn/lypi/transport/headless/HeadlessSubagentRunnerTest.java`
- `lypi-transport-headless/src/test/java/cn/lypi/transport/headless/HeadlessSubagentJsonCodecTest.java`
- `lypi-transport-headless/src/test/java/cn/lypi/transport/headless/PermissionRuntimeHeadlessEndToEndTest.java`

## Before Editing

- Decide whether the change affects tool schema, runtime lifecycle, child session metadata, process protocol or mailbox.
- Test both success and failure or timeout paths.
- For protocol changes, update codec and runner tests together.
- For tool policy changes, test spawn and continue with explicit tools and permission modes.
- For permission runtime changes, test spawn inherit, spawn explicit override, continue inherit, continue explicit override and legacy JSON compatibility.
- For mailbox changes, test pending, delivered, stashed and discarded projections.

After using this Skill, reverse-check the listed source paths against current code and report stale knowledge if they differ.
