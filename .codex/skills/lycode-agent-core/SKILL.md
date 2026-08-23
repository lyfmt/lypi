---
name: lycode-agent-core
description: Use when changing or debugging ly-code turn execution, context assembly, model stream handling, tool rounds, compaction, branch summaries, or memory extraction hooks.
---

# ly-code Agent Core

## Core Rule

`lycode-agent-core` orchestrates a turn through ports. It should not know about concrete terminal UI, concrete provider protocols, or concrete tool implementations.

## Main Responsibilities

- Append the user message and assistant/tool result messages through session ports.
- Build model context from session and resource runtime.
- Stream provider events into internal assistant message blocks.
- Map assistant tool calls to `ToolUseRequest`.
- Execute tool rounds through `ToolRuntimePort`.
- Run micro-compaction and preflight compaction before model calls.
- Publish turn lifecycle events with stable fork points; runtime owns background memory consolidation after turn end.

## Key Code

- `lycode-agent-core/src/main/java/cn/lycode/agent/DefaultTurnExecutor.java`
- `lycode-agent-core/src/main/java/cn/lycode/agent/DefaultContextAssembler.java`
- `lycode-agent-core/src/main/java/cn/lycode/agent/AgentCoreRuntimePorts.java`
- `lycode-agent-core/src/main/java/cn/lycode/agent/AssistantStreamAccumulator.java`
- `lycode-agent-core/src/main/java/cn/lycode/agent/ToolCallMapper.java`
- `lycode-agent-core/src/main/java/cn/lycode/agent/AgentCoreExceptionHandler.java`
- `lycode-agent-core/src/main/java/cn/lycode/agent/compact/DefaultCompactionCoordinator.java`
- `lycode-agent-core/src/main/java/cn/lycode/agent/compact/DefaultCompactionPlanner.java`
- `lycode-agent-core/src/main/java/cn/lycode/agent/branch/AiBranchSummarizer.java`

## Turn Flow

1. `DefaultTurnExecutor.execute()` opens the session and optionally switches parent entry.
2. It rejects unsafe continuation when the current leaf is an assistant tool-call message.
3. It appends the user message.
4. `DefaultContextAssembler.build()` loads resources, builds system prompt, projects session context and estimates budget.
5. Tool micro-compaction and `CompactionCoordinator.preflight()` may rewrite the context view.
6. Provider stream events are accumulated and mirrored as message events.
7. Tool calls are mapped and executed through `ToolRuntimePort`.
8. Tool results are appended, context is rebuilt, and the model continues.
9. Completed, failed and aborted turns publish `TurnEndEvent`; successful long-enough turns are eligible for runtime background memory consolidation.

## Invariants

- `DefaultTurnExecutor` returns tool results in the same order as tool requests.
- Incomplete tool-call deltas end the turn with an error message.
- User abort produces `TurnStatus.ABORTED`; successful completion is required before runtime memory consolidation can trigger.
- `TurnEndEvent.leafEntryId` is the stable fork point for background memory consolidation; runtime listeners must not depend on the mutable current session leaf.
- Tool runtime cwd must match agent cwd.
- Skill mentions are injected by `DefaultContextAssembler` only when present in the request.
- Compaction should preserve valid API round structure; do not cut through an open tool call/result pair.

## Tests To Check

- `lycode-agent-core/src/test/java/cn/lycode/agent/DefaultTurnExecutorTest.java`
- `lycode-agent-core/src/test/java/cn/lycode/agent/DefaultTurnExecutorPermissionTest.java`
- `lycode-agent-core/src/test/java/cn/lycode/agent/DefaultContextAssemblerTest.java`
- `lycode-agent-core/src/test/java/cn/lycode/agent/AssistantStreamAccumulatorTest.java`
- `lycode-agent-core/src/test/java/cn/lycode/agent/ToolCallMapperTest.java`
- `lycode-agent-core/src/test/java/cn/lycode/agent/DefaultCompactionCoordinatorTest.java`
- `lycode-agent-core/src/test/java/cn/lycode/agent/DefaultCompactionPlannerTest.java`

## Before Editing

- Identify whether the behavior belongs in core or in a port implementation.
- Add tests around `TurnState`, emitted events and session entries, not only return text.
- For provider event changes, update accumulator tests.
- For tool-call changes, test malformed and complete tool deltas.
- For compaction changes, test API-round boundaries and prompt-too-long recovery.

## Common Changes

| Need | Likely Files |
| --- | --- |
| Change turn lifecycle | `DefaultTurnExecutor`, turn executor tests |
| Change context inputs | `DefaultContextAssembler`, resource/session tests |
| Change tool-call parsing | `ToolCallMapper`, accumulator tests |
| Change compaction trigger | `DefaultCompactionCoordinator`, planner tests |
| Change memory hook | `TurnEventPublisher`, `MemoryConsolidationTurnEndListener`, turn completion tests |

After using this Skill, reverse-check that no new dependency on TUI, provider implementation, or concrete tool classes was introduced.
