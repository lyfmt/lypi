---
name: lycode-session-engine
description: Use when changing or investigating ly-code session JSONL storage, branch trees, replay context, fork, child sessions, resume queries, or file diff session views.
---

# ly-code Session Engine

## Core Rule

Sessions are append-only entry trees. Do not rewrite old entries to express a new state; append a new entry and move or query the leaf.

## Boundaries

`lycode-session` owns session storage, branch indexing, replay projection, fork and child session creation. It must not perform model calls, tool execution, TUI rendering, or provider-specific logic.

Shared entry records and views live in `lycode-contracts/src/main/java/cn/lycode/contracts/session/`.

## Key Code

- `lycode-session/src/main/java/cn/lycode/session/SessionManagerImpl.java`
- `lycode-session/src/main/java/cn/lycode/session/JsonlSessionStore.java`
- `lycode-session/src/main/java/cn/lycode/session/EntryTreeIndex.java`
- `lycode-session/src/main/java/cn/lycode/session/SessionReplayProjector.java`
- `lycode-session/src/main/java/cn/lycode/session/ForkService.java`
- `lycode-session/src/main/java/cn/lycode/session/ChildSessionService.java`
- `lycode-session/src/main/java/cn/lycode/session/SessionResumeQuery.java`
- `lycode-session/src/main/java/cn/lycode/session/SessionResumeScan.java`
- `lycode-session/src/main/java/cn/lycode/session/SessionEntryDisplayText.java`
- `lycode-session/src/main/java/cn/lycode/session/SessionBranchTreeQuery.java`
- `lycode-session/src/main/java/cn/lycode/session/SessionFileQuery.java`
- `lycode-session/src/main/java/cn/lycode/session/GitDiffQuery.java`
- `lycode-session/src/main/java/cn/lycode/session/GitWorkingTreeDiffQuery.java`

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
- JSONL full reads must parse line by line; header/list queries must read only the header line.
- Resume queries use `JsonlSessionStore.resumeScans()` lightweight metadata scans, bounded by `MAX_CONCURRENT_SESSION_INFO_LOADS`, and must not full replay every session.
- Header `id` must match the JSONL file name; list/resume paths skip unreadable or mismatched files, while direct reads fail loudly.
- `switchLeaf()` moves the view only; it does not rewrite transcript.
- `MessageEntry`, `BranchSummaryEntry`, `CustomMessageEntry`, and `CompactionEntry` can affect model-visible context.
- Model, thinking, agent mode and permission runtime state are restored from entry history or child session header defaults.
- Do not store turn-scoped permission amendments or `strictAutoReview` in session replay state unless a dedicated durable entry exists.
- `CompactionEntry` injects a summary and keeps entries from `firstKeptEntryId`.
- Forked sessions are new session files connected by metadata, not in-place branch edits.
- Child sessions are independent sessions linked to parent session and spawn entry.

## Tests To Check

- `lycode-session/src/test/java/cn/lycode/session/SessionManagerImplTest.java`
- `lycode-session/src/test/java/cn/lycode/session/SessionManagerReplayTest.java`
- `lycode-session/src/test/java/cn/lycode/session/SessionEntryBoundaryTest.java`
- `lycode-contracts/src/test/java/cn/lycode/contracts/session/PermissionRuntimeStateEntryTest.java`
- `lycode-session/src/test/java/cn/lycode/session/SessionBranchTreeQueryTest.java`
- `lycode-session/src/test/java/cn/lycode/session/SessionResumeQueryTest.java`
- `lycode-session/src/test/java/cn/lycode/session/SessionFileQueryTest.java`
- `lycode-session/src/test/java/cn/lycode/session/GitDiffQueryTest.java`
- `lycode-session/src/test/java/cn/lycode/session/ChildSessionServiceTest.java`

## Before Editing

- Identify the entry type involved and whether it belongs in contracts first.
- Verify replay behavior in `SessionReplayProjector` before changing storage.
- For new entries, add serialization coverage in contracts or session tests.
- For branch behavior, test both linear and diverged histories.
- For child session behavior, check parent metadata, session header defaults and canonical permission runtime inheritance.

## Common Changes

| Need | Likely Files |
| --- | --- |
| New entry type | `lycode-contracts/src/main/java/cn/lycode/contracts/session/`, `SessionJsonMapper`, replay tests |
| Change context reconstruction | `SessionReplayProjector`, `SessionManagerReplayTest` |
| Change branch selection | `EntryTreeIndex`, `SessionLeafSelector`, branch query tests |
| Change resume UI data | `SessionResumeQuery`, `SessionResumeScan`, `SessionEntryDisplayText`, `SessionBranchTreeQuery`, TUI contract tests |
| Change child session metadata | `ChildSessionService`, subagent runtime tests |
| Change permission runtime replay | `PermissionRuntimeStateChangeEntry`, `SessionReplayProjector`, contracts serialization tests |

After using this Skill, verify listed paths still exist and compare any doc claim against the current session tests.
