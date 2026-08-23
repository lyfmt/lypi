---
name: lycode-architecture
description: Use when changing or reviewing ly-code module boundaries, Maven module dependencies, contracts, runtime ports, event contracts, or cross-cutting architecture.
---

# ly-code Architecture

## Core Rule

Keep module boundaries explicit and interface-first. Upper layers should depend on contracts and ports, not concrete UI, provider, or tool implementations.

## Applies To

Use this skill for architecture decisions, new modules, dependency changes, shared contracts, event model changes, or behavior that crosses `lycode-session`, `lycode-agent-core`, `lycode-tool`, `lycode-resource`, runtime, and transports.

Skip it for isolated implementation details inside a single module unless the change leaks across public contracts.

## Current Modules

The root `pom.xml` defines these Maven modules:

- `lycode-contracts`: shared records, ports, events, session entries, errors, security enums, TUI views, Skill and MCP contracts.
- `lycode-session`: append-only session storage, branch/replay queries, fork and child session creation.
- `lycode-agent-core`: turn execution, context assembly, stream accumulation, tool rounds, compaction, branch summaries.
- `lycode-ai`: provider adapters, model registry, request building, stream normalization and fallback.
- `lycode-tool`: tool registry/runtime, built-in tools, MCP adapter, permission gate integration and shell executors.
- `lycode-security`: policy engine, path safety, Bash normalization, risk analysis and rule matching.
- `lycode-resource`: context files, memory, Skill index, prompt templates, MCP config and system prompt construction.
- `lycode-runtime`: event bus, memory consolidation, subagent center, mailbox and process runner.
- `lycode-transport-headless`: headless subagent JSON protocol.
- `lycode-transport-tui`: JLine TUI, input loop, event reducer, renderer, slash commands and overlays.
- `lycode-boot`: Spring Boot assembly, config binding and startup modes.

## Permission Architecture

- `PermissionRuntimeState` in `lycode-contracts` is the canonical cross-module permission state. It carries approval policy, active profile, legacy behavior and legacy mode compatibility.
- `PermissionMode` remains only for legacy constructors, old JSON, UI compatibility and fallback mapping. New cross-module contracts should include `PermissionRuntimeState`.
- `lycode-security` compiles permission profiles and evaluates filesystem/network/hard-safety policy.
- `lycode-tool` coordinates approval prompts, permission amendments, `request_permissions`, sandbox projection and tool execution.
- `lycode-session`, `lycode-agent-core`, `lycode-resource`, `lycode-runtime`, `lycode-transport-headless`, `lycode-transport-tui` and `lycode-boot` consume the same canonical runtime state instead of reinterpreting legacy modes.
- Headless/subagent JSON writes `permissionRuntimeState` for new protocol and reads legacy `permissionMode` for compatibility.

## Invariants

- `lycode-contracts` is the shared boundary; other modules should not expose their internals as cross-module state.
- `lycode-agent-core` must not directly bind to TUI, concrete providers, or concrete tools. Use ports in `cn.lycode.contracts.runtime`.
- Transports adapt input/output and display. They should not own durable session or tool state.
- Session history is append-only JSONL; branch movement changes the leaf, not old entries.
- Permission runtime changes are represented by session entries and replayed into `SessionContext`; do not mutate historical entries to change permission state.
- Tool calls, permission decisions, retry, provider fallback, compaction and UI updates flow through contract events where possible.
- Provider retry and fallback notices are stream control events: agent core maps them to lifecycle events, TUI projects transient state, and neither enters the durable transcript.
- Permission request/decision events expose approval kind, available decisions and additional permission metadata for TUI/headless rendering.
- Memory consolidation is driven by `TurnEndEvent` after the main turn completes; `DefaultTurnExecutor` must not synchronously call legacy `MemoryExtractionWorker` on the user-facing path.
- `TurnEndEvent.leafEntryId` is the stable fork point for background consolidation. Runtime listeners must use this event field instead of the mutable `SessionManagerPort.currentView().leafId()`.
- Memory consolidation trigger gates follow Claude Code session memory style: initialize after about 10,000 estimated context tokens, require about 5,000 token growth between updates, and trigger on either enough tool calls or a natural assistant turn without tool calls. Do not use wall-clock duration as the durable trigger.
- Background memory consolidation skips only when the main turn has a completed memory write tool call, including conservative Bash command detection, with a successful matching tool result; failed or rejected write attempts must still allow background consolidation.
- Runtime turn-end listeners must not replay transcripts or run direct-write detection on the synchronous event dispatch path; transcript inspection belongs inside the background executor so the main turn response is not blocked.
- Background memory consolidation is best-effort and auditable: runtime records threshold/session/direct-write/coalesced states, boot runner runs preflight memory scan and injects the scan summary into the hidden settlement turn, then records post-turn lint diagnostics without blocking the main turn.
- Background memory consolidation must preserve the parent tool schema for prompt-cache prefix stability; restrict actual execution with a can-use-tool style runtime gate and memory write policy instead of filtering the visible tool snapshot.
- Memory lint is an automatic background diagnostic only; do not expose product slash commands or default user resources for manual `/memory-lint`.
- Product runtime Skill discovery is under `skills/` and `.ly-code/skills/`; repository Codex knowledge under `.codex/skills/` is not a product resource root.

## Key Anchors

- `pom.xml`
- `README.md`
- `docs/permission-system-codex-alignment-design.md`
- `docs/permission-system-codex-alignment-plan.md`
- `lycode-contracts/src/main/java/cn/lycode/contracts/runtime/`
- `lycode-contracts/src/main/java/cn/lycode/contracts/event/`
- `lycode-contracts/src/main/java/cn/lycode/contracts/event/TurnEndEvent.java`
- `lycode-runtime/src/main/java/cn/lycode/runtime/memory/MemoryConsolidationTurnEndListener.java`
- `lycode-runtime/src/main/java/cn/lycode/runtime/memory/MemoryWriteDetector.java`
- `lycode-runtime/src/main/java/cn/lycode/runtime/memory/MemoryLintScanner.java`
- `lycode-runtime/src/main/java/cn/lycode/runtime/memory/MemoryPreflightScan.java`
- `lycode-boot/src/main/java/cn/lycode/boot/runtime/BootMemoryConsolidationRunner.java`
- `lycode-contracts/src/main/java/cn/lycode/contracts/security/PermissionRuntimeState.java`
- `lycode-contracts/src/main/java/cn/lycode/contracts/security/PermissionProfiles.java`
- `lycode-contracts/src/main/java/cn/lycode/contracts/session/PermissionRuntimeStateChangeEntry.java`
- `lycode-contracts/src/test/java/cn/lycode/contracts/ArchitectureBoundaryTest.java`
- `lycode-boot/src/main/java/cn/lycode/boot/LyCodeApplication.java`

## Before Changing Architecture

- Check whether a contract already exists before adding a new dependency.
- Search imports to confirm dependency direction with `rg -n "import cn\\.lycode\\." <module>`.
- Add or update a boundary test if a public dependency rule changes.
- Prefer adding a port or contract type over reaching into another module implementation.
- For permission work, keep `PermissionRuntimeState` in contracts and pass it through ports instead of adding module-local permission state shapes.
- Check `README.md` and active docs only after confirming the source-level boundary.

## Common Change Patterns

| Change | Preferred Location |
| --- | --- |
| New shared data shape | `lycode-contracts` |
| New durable session fact | `lycode-contracts/src/main/java/cn/lycode/contracts/session/` plus `lycode-session` projector/store tests |
| New turn orchestration behavior | `lycode-agent-core` through runtime ports |
| New UI affordance | `lycode-transport-tui` consuming events or contracts |
| New tool execution capability | `lycode-tool` plus security/runtime port checks |
| New resource type | `lycode-resource` and prompt builder tests |
| New permission runtime field | `lycode-contracts` plus session/headless/boot compatibility tests |

After using this Skill, reverse-check one listed invariant against current code and report stale knowledge if found.
