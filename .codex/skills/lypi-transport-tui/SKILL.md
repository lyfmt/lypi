---
name: lypi-transport-tui
description: Use when changing ly-pi terminal UI, JLine input, TUI event reduction, rendering, slash commands, overlays, permission prompts, diff display, file mentions, or Skill mentions.
---

# ly-pi Transport TUI

## Core Rule

The TUI adapts input and renders state. Durable behavior belongs in session, agent core, tool runtime, resource runtime or contracts.

## Main Responsibilities

- Open a JLine terminal transport and attach to `AgentEvent`.
- Convert semantic events into `TuiViewModel`.
- Route user input, slash commands and compact commands.
- Parse file and Skill mentions for user input.
- Render message, thinking, tool, error, permission, diff and status views.
- Forward submissions to `AgentCorePort` through `TurnRequest`.

## Key Code

- `lypi-transport-tui/src/main/java/cn/lypi/transport/tui/JLineTuiTransport.java`
- `lypi-transport-tui/src/main/java/cn/lypi/transport/tui/RuntimeTuiSubmitHandler.java`
- `lypi-transport-tui/src/main/java/cn/lypi/transport/tui/TuiEventReducer.java`
- `lypi-transport-tui/src/main/java/cn/lypi/transport/tui/TuiTranscriptPartitioner.java`
- `lypi-transport-tui/src/main/java/cn/lypi/transport/tui/TuiTranscriptCommitLedger.java`
- `lypi-transport-tui/src/main/java/cn/lypi/transport/tui/TuiRenderBatch.java`
- `lypi-transport-tui/src/main/java/cn/lypi/transport/tui/TuiLayout.java`
- `lypi-transport-tui/src/main/java/cn/lypi/transport/tui/TuiRenderer.java`
- `lypi-transport-tui/src/main/java/cn/lypi/transport/tui/InlineViewport.java`
- `lypi-transport-tui/src/main/java/cn/lypi/transport/tui/InlineTerminalRenderer.java`
- `lypi-transport-tui/src/main/java/cn/lypi/transport/tui/TerminalCursorProbe.java`
- `lypi-transport-tui/src/main/java/cn/lypi/transport/tui/TerminalSession.java`
- `lypi-transport-tui/src/main/java/cn/lypi/transport/tui/TuiRenderState.java`
- `lypi-transport-tui/src/main/java/cn/lypi/transport/tui/TuiInputLoop.java`
- `lypi-transport-tui/src/main/java/cn/lypi/transport/tui/SlashCommandRouter.java`
- `lypi-transport-tui/src/main/java/cn/lypi/transport/tui/PermissionOverlay.java`
- `lypi-transport-tui/src/main/java/cn/lypi/transport/tui/DiffOverlay.java`
- `lypi-transport-tui/src/main/java/cn/lypi/transport/tui/SkillMentionParser.java`
- `lypi-transport-tui/src/main/java/cn/lypi/transport/tui/FileMentionPicker.java`

## Event Flow

1. `JLineTuiTransport.open()` enters normal-screen interactive modes, probes the cursor with a timeout and replays non-CPR input.
2. `RuntimeTuiSubmitHandler.submitUserInput()` routes slash commands and resolves Skill mentions.
3. Normal input becomes `TurnRequest` and executes asynchronously.
4. `TuiEventReducer.reduce()` projects message, tool, permission, retry, compact, interrupt and session events.
5. `TuiTranscriptPartitioner` splits the stable prefix from the live tail without storing durable state.
6. `TuiTranscriptCommitLedger` emits each stable block once per `(sessionId, leafId)` projection.
7. `TuiRenderer` renders new committed blocks separately from the bounded live/input/overlay/status surface.
8. `InlineTerminalRenderer` inserts committed lines above the viewport and diffs only the mutable surface in one synchronized terminal transaction.

## Invariants

- Reducers should not invent durable transcript content. They project events into display state.
- The first streaming, active, pending or running block starts the live tail; only the stable prefix can be committed.
- A stable block ID is committed at most once per projection key, including after transient stable/live regressions.
- Committed transcript is terminal-native scrollback. There is no application-side 500-line history window or scroll offset.
- PageUp, PageDown and mouse wheel never enter an application history model or mutate the draft; scrollback remains terminal/tmux-owned.
- Historical tools always use their completed collapsed rendering. Ctrl+O affects tools in the live region only.
- The mutable surface is bounded by `terminalHeight - 1` and contains only live content, input, overlays and status.
- `TerminalSession` must not enable 1049, 1000 or 1006; close and partial failure restore raw mode, cursor and interactive modes.
- Resize probes the post-reflow cursor, preserves concurrent input, never replays committed blocks and redraws only the mutable surface.
- Finalizing a streaming block commits its final text once in the same transaction that removes it from the surface.
- Close clears the mutable surface while preserving committed transcript and the shell cursor handoff position.
- A projection-key change opens a new commit epoch without clearing earlier terminal scrollback.
- Slash commands that change durable state should route through session/runtime contracts, not UI-only variables.
- Permission prompts display decision context and should clear on decision or interrupt.
- Skill mentions are resolved from the current `SkillIndex`, then passed into `TurnRequest`.
- Long-running work should not hold the UI lock while executing core logic.

## Tests To Check

- `lypi-transport-tui/src/test/java/cn/lypi/transport/tui/TerminalSessionTest.java`
- `lypi-transport-tui/src/test/java/cn/lypi/transport/tui/TerminalCursorProbeTest.java`
- `lypi-transport-tui/src/test/java/cn/lypi/transport/tui/InlineViewportTest.java`
- `lypi-transport-tui/src/test/java/cn/lypi/transport/tui/InlineTerminalRendererTest.java`
- `lypi-transport-tui/src/test/java/cn/lypi/transport/tui/TuiTranscriptPartitionerTest.java`
- `lypi-transport-tui/src/test/java/cn/lypi/transport/tui/TuiTranscriptCommitLedgerTest.java`
- `lypi-transport-tui/src/test/java/cn/lypi/transport/tui/TuiLayoutTest.java`
- `lypi-transport-tui/src/test/java/cn/lypi/transport/tui/TuiRendererTest.java`
- `lypi-transport-tui/src/test/java/cn/lypi/transport/tui/JLineTuiTransportTest.java`
- `lypi-transport-tui/src/test/java/cn/lypi/transport/tui/JLineTuiTransportConcurrencyTest.java`
- `lypi-transport-tui/src/test/java/cn/lypi/transport/tui/JLineTuiTransportRenderPipelineTest.java`
- `lypi-transport-tui/src/test/java/cn/lypi/transport/tui/RuntimeTuiSubmitHandlerTest.java`
- `lypi-transport-tui/src/test/java/cn/lypi/transport/tui/TuiEventReducerTest.java`
- `lypi-transport-tui/src/test/java/cn/lypi/transport/tui/PermissionOverlayTest.java`
- `lypi-transport-tui/src/test/java/cn/lypi/transport/tui/DiffOverlayTest.java`
- `lypi-transport-tui/src/test/java/cn/lypi/transport/tui/FileMentionPickerTest.java`
- `lypi-transport-tui/src/test/java/cn/lypi/transport/tui/SlashCommandRouterTest.java`
- `lypi-transport-tui/src/test/java/cn/lypi/transport/tui/SkillMentionParserTest.java`
- `lypi-transport-tui/src/test/java/cn/lypi/transport/tui/TuiContractEndToEndTest.java`
- `lypi-transport-tui/src/test/resources/run-tui-frame-pty.sh`
- `lypi-transport-tui/src/test/resources/run-tui-interaction-pty.sh`
- `lypi-transport-tui/src/test/resources/run-tui-smoke.sh`

## Before Editing

- Decide whether the change is view projection, rendering, input handling or runtime behavior.
- If it changes data shape, update `lypi-contracts` view model records first.
- Prefer event-driven state updates over querying internals from the UI layer.
- For rendering changes, test narrow width, multiline input, streaming finalization, resize reflow and surface-height bounds.
- For terminal lifecycle changes, test partial open failure and real tmux server/client PTYs before relying on control-sequence unit tests alone.
- For slash commands, test consumed, prompt-rewrite and state-change paths.

After using this Skill, reverse-check that the change did not move business state into TUI classes.
