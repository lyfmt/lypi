---
name: lypi-session-engine
description: Use when changing or investigating ly-pi session JSONL storage, branch trees, replay context, fork, child sessions, resume queries, or file diff session views.
---

# ly-pi Session Engine

## Core Rule

Sessions are append-only entry trees. Do not rewrite old entries to express a new state; append a new entry and move or query the leaf.

## Boundaries

`lypi-session` owns session storage, branch indexing, replay projection, fork and child session creation. It must not perform model calls, tool execution, TUI rendering, or provider-specific logic.

Shared entry records and views live in `lypi-contracts/src/main/java/cn/lypi/contracts/session/`.

## Key Code

- `lypi-session/src/main/java/cn/lypi/session/SessionManagerImpl.java`
- `lypi-session/src/main/java/cn/lypi/session/JsonlSessionStore.java`
- `lypi-session/src/main/java/cn/lypi/session/EntryTreeIndex.java`
- `lypi-session/src/main/java/cn/lypi/session/SessionReplayProjector.java`
- `lypi-session/src/main/java/cn/lypi/session/ForkService.java`
- `lypi-session/src/main/java/cn/lypi/session/ChildSessionService.java`
- `lypi-session/src/main/java/cn/lypi/session/SessionResumeQuery.java`
- `lypi-session/src/main/java/cn/lypi/session/SessionBranchTreeQuery.java`
- `lypi-session/src/main/java/cn/lypi/session/SessionFileQuery.java`
- `lypi-session/src/main/java/cn/lypi/session/GitDiffQuery.java`
- `lypi-session/src/main/java/cn/lypi/session/GitWorkingTreeDiffQuery.java`

## Main Flow

1. `SessionManagerImpl.openOrCreate()` creates or loads a JSONL session file.
2. `EntryTreeIndex` validates append order and maintains the current leaf.
3. `append()` adds a `SessionEntry` and advances the leaf.
4. `branch(leafId)` returns the root-to-leaf path.
5. `SessionReplayProjector.context()` projects branch entries into `SessionContext`.
6. `SessionReplayProjector` applies model, thinking, mode, canonical permission runtime, branch summary and compaction entries during replay.

## Permission Replay

- `SessionContext.permissionRuntimeState()` is canonical; `permissionMode()` is a compatibility getter derived from the runtime state's legacy mode.
- `PermissionRuntimeStateChangeEntry` records canonical permission runtime changes. Its legacy `permissionMode` JSON is read for old session files.
- Old `PermissionModeChangeEntry` is still replayed by mapping through `PermissionRuntimeState.fromLegacy(...)`.
- Child session headers can carry initial canonical permission runtime state; replay starts from that header value when present.

## Invariants

- Entries have stable `id` and `parentId`; branching is represented by parent links.
- `switchLeaf()` moves the view only; it does not rewrite transcript.
- `MessageEntry`, `BranchSummaryEntry`, `CustomMessageEntry`, and `CompactionEntry` can affect model-visible context.
- Model, thinking, agent mode and permission runtime state are restored from entry history or child session header defaults.
- Do not store turn-scoped permission amendments or `strictAutoReview` in session replay state unless a dedicated durable entry exists.
- `CompactionEntry` injects a summary and keeps entries from `firstKeptEntryId`.
- Forked sessions are new session files connected by metadata, not in-place branch edits.
- Child sessions are independent sessions linked to parent session and spawn entry.

## Tests To Check

- `lypi-session/src/test/java/cn/lypi/session/SessionManagerImplTest.java`
- `lypi-session/src/test/java/cn/lypi/session/SessionManagerReplayTest.java`
- `lypi-session/src/test/java/cn/lypi/session/SessionEntryBoundaryTest.java`
- `lypi-contracts/src/test/java/cn/lypi/contracts/session/PermissionRuntimeStateEntryTest.java`
- `lypi-session/src/test/java/cn/lypi/session/SessionBranchTreeQueryTest.java`
- `lypi-session/src/test/java/cn/lypi/session/SessionResumeQueryTest.java`
- `lypi-session/src/test/java/cn/lypi/session/SessionFileQueryTest.java`
- `lypi-session/src/test/java/cn/lypi/session/GitDiffQueryTest.java`
- `lypi-session/src/test/java/cn/lypi/session/ChildSessionServiceTest.java`

## Before Editing

- Identify the entry type involved and whether it belongs in contracts first.
- Verify replay behavior in `SessionReplayProjector` before changing storage.
- For new entries, add serialization coverage in contracts or session tests.
- For branch behavior, test both linear and diverged histories.
- For child session behavior, check parent metadata, session header defaults and canonical permission runtime inheritance.

## Common Changes

| Need | Likely Files |
| --- | --- |
| New entry type | `lypi-contracts/src/main/java/cn/lypi/contracts/session/`, `SessionJsonMapper`, replay tests |
| Change context reconstruction | `SessionReplayProjector`, `SessionManagerReplayTest` |
| Change branch selection | `EntryTreeIndex`, `SessionLeafSelector`, branch query tests |
| Change resume UI data | `SessionResumeQuery`, `SessionBranchTreeQuery`, TUI contract tests |
| Change child session metadata | `ChildSessionService`, subagent runtime tests |
| Change permission runtime replay | `PermissionRuntimeStateChangeEntry`, `SessionReplayProjector`, contracts serialization tests |

After using this Skill, verify listed paths still exist and compare any doc claim against the current session tests.
