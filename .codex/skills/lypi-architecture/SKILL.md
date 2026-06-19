---
name: lypi-architecture
description: Use when changing or reviewing ly-pi module boundaries, Maven module dependencies, contracts, runtime ports, event contracts, or cross-cutting architecture.
---

# ly-pi Architecture

## Core Rule

Keep module boundaries explicit and interface-first. Upper layers should depend on contracts and ports, not concrete UI, provider, or tool implementations.

## Applies To

Use this skill for architecture decisions, new modules, dependency changes, shared contracts, event model changes, or behavior that crosses `lypi-session`, `lypi-agent-core`, `lypi-tool`, `lypi-resource`, runtime, and transports.

Skip it for isolated implementation details inside a single module unless the change leaks across public contracts.

## Current Modules

The root `pom.xml` defines these Maven modules:

- `lypi-contracts`: shared records, ports, events, session entries, errors, security enums, TUI views, Skill and MCP contracts.
- `lypi-session`: append-only session storage, branch/replay queries, fork and child session creation.
- `lypi-agent-core`: turn execution, context assembly, stream accumulation, tool rounds, compaction, branch summaries.
- `lypi-ai`: provider adapters, model registry, request building, stream normalization and fallback.
- `lypi-tool`: tool registry/runtime, built-in tools, MCP adapter, permission gate integration and shell executors.
- `lypi-security`: policy engine, path safety, Bash normalization, risk analysis and rule matching.
- `lypi-resource`: context files, memory, Skill index, prompt templates, MCP config and system prompt construction.
- `lypi-runtime`: event bus, memory consolidation, subagent center, mailbox and process runner.
- `lypi-transport-headless`: headless subagent JSON protocol.
- `lypi-transport-tui`: JLine TUI, input loop, event reducer, renderer, slash commands and overlays.
- `lypi-boot`: Spring Boot assembly, config binding and startup modes.

## Permission Architecture

- `PermissionRuntimeState` in `lypi-contracts` is the canonical cross-module permission state. It carries approval policy, active profile, legacy behavior and legacy mode compatibility.
- `PermissionMode` remains only for legacy constructors, old JSON, UI compatibility and fallback mapping. New cross-module contracts should include `PermissionRuntimeState`.
- `lypi-security` compiles permission profiles and evaluates filesystem/network/hard-safety policy.
- `lypi-tool` coordinates approval prompts, permission amendments, `request_permissions`, sandbox projection and tool execution.
- `lypi-session`, `lypi-agent-core`, `lypi-resource`, `lypi-runtime`, `lypi-transport-headless`, `lypi-transport-tui` and `lypi-boot` consume the same canonical runtime state instead of reinterpreting legacy modes.
- Headless/subagent JSON writes `permissionRuntimeState` for new protocol and reads legacy `permissionMode` for compatibility.

## Invariants

- `lypi-contracts` is the shared boundary; other modules should not expose their internals as cross-module state.
- `lypi-agent-core` must not directly bind to TUI, concrete providers, or concrete tools. Use ports in `cn.lypi.contracts.runtime`.
- Transports adapt input/output and display. They should not own durable session or tool state.
- Session history is append-only JSONL; branch movement changes the leaf, not old entries.
- Permission runtime changes are represented by session entries and replayed into `SessionContext`; do not mutate historical entries to change permission state.
- Tool calls, permission decisions, retry, compaction and UI updates flow through contract events where possible.
- Permission request/decision events expose approval kind, available decisions and additional permission metadata for TUI/headless rendering.
- Memory consolidation is driven by `TurnEndEvent` after the main turn completes; `DefaultTurnExecutor` must not synchronously call legacy `MemoryExtractionWorker` on the user-facing path.
- `TurnEndEvent.leafEntryId` is the stable fork point for background consolidation. Runtime listeners must use this event field instead of the mutable `SessionManagerPort.currentView().leafId()`.
- Memory consolidation trigger gates follow Claude Code session memory style: initialize after about 10,000 estimated context tokens, require about 5,000 token growth between updates, and trigger on either enough tool calls or a natural assistant turn without tool calls. Do not use wall-clock duration as the durable trigger.
- Background memory consolidation skips only when the main turn has a completed memory write tool call, including conservative Bash command detection, with a successful matching tool result; failed or rejected write attempts must still allow background consolidation.
- Runtime turn-end listeners must not replay transcripts or run direct-write detection on the synchronous event dispatch path; transcript inspection belongs inside the background executor so the main turn response is not blocked.
- Background memory consolidation is best-effort and auditable: runtime records threshold/session/direct-write/coalesced states, boot runner runs preflight memory scan and injects the scan summary into the hidden settlement turn, then records post-turn lint diagnostics without blocking the main turn.
- Memory lint is an automatic background diagnostic only; do not expose product slash commands or default user resources for manual `/memory-lint`.
- Product runtime Skill discovery is under `skills/` and `.ly-pi/skills/`; repository Codex knowledge under `.codex/skills/` is not a product resource root.

## Key Anchors

- `pom.xml`
- `README.md`
- `docs/permission-system-codex-alignment-design.md`
- `docs/permission-system-codex-alignment-plan.md`
- `lypi-contracts/src/main/java/cn/lypi/contracts/runtime/`
- `lypi-contracts/src/main/java/cn/lypi/contracts/event/`
- `lypi-contracts/src/main/java/cn/lypi/contracts/event/TurnEndEvent.java`
- `lypi-runtime/src/main/java/cn/lypi/runtime/memory/MemoryConsolidationTurnEndListener.java`
- `lypi-runtime/src/main/java/cn/lypi/runtime/memory/MemoryWriteDetector.java`
- `lypi-runtime/src/main/java/cn/lypi/runtime/memory/MemoryLintScanner.java`
- `lypi-runtime/src/main/java/cn/lypi/runtime/memory/MemoryPreflightScan.java`
- `lypi-boot/src/main/java/cn/lypi/boot/runtime/BootMemoryConsolidationRunner.java`
- `lypi-contracts/src/main/java/cn/lypi/contracts/security/PermissionRuntimeState.java`
- `lypi-contracts/src/main/java/cn/lypi/contracts/security/PermissionProfiles.java`
- `lypi-contracts/src/main/java/cn/lypi/contracts/session/PermissionRuntimeStateChangeEntry.java`
- `lypi-contracts/src/test/java/cn/lypi/contracts/ArchitectureBoundaryTest.java`
- `lypi-boot/src/main/java/cn/lypi/boot/LyPiApplication.java`

## Before Changing Architecture

- Check whether a contract already exists before adding a new dependency.
- Search imports to confirm dependency direction with `rg -n "import cn\\.lypi\\." <module>`.
- Add or update a boundary test if a public dependency rule changes.
- Prefer adding a port or contract type over reaching into another module implementation.
- For permission work, keep `PermissionRuntimeState` in contracts and pass it through ports instead of adding module-local permission state shapes.
- Check `README.md` and active docs only after confirming the source-level boundary.

## Common Change Patterns

| Change | Preferred Location |
| --- | --- |
| New shared data shape | `lypi-contracts` |
| New durable session fact | `lypi-contracts/src/main/java/cn/lypi/contracts/session/` plus `lypi-session` projector/store tests |
| New turn orchestration behavior | `lypi-agent-core` through runtime ports |
| New UI affordance | `lypi-transport-tui` consuming events or contracts |
| New tool execution capability | `lypi-tool` plus security/runtime port checks |
| New resource type | `lypi-resource` and prompt builder tests |
| New permission runtime field | `lypi-contracts` plus session/headless/boot compatibility tests |

After using this Skill, reverse-check one listed invariant against current code and report stale knowledge if found.
