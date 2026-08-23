---
name: lycode-transport-tui
description: Use when changing ly-code terminal UI, JLine input, TUI event reduction, rendering, slash commands, overlays, permission prompts, diff display, file mentions, or Skill mentions.
---

# ly-code Transport TUI

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

- `lycode-transport-tui/src/main/java/cn/lycode/transport/tui/JLineTuiTransport.java`
- `lycode-transport-tui/src/main/java/cn/lycode/transport/tui/RuntimeTuiSubmitHandler.java`
- `lycode-transport-tui/src/main/java/cn/lycode/transport/tui/TuiEventReducer.java`
- `lycode-transport-tui/src/main/java/cn/lycode/transport/tui/TuiTranscriptPartitioner.java`
- `lycode-transport-tui/src/main/java/cn/lycode/transport/tui/TuiTranscriptCommitLedger.java`
- `lycode-transport-tui/src/main/java/cn/lycode/transport/tui/TuiRenderBatch.java`
- `lycode-transport-tui/src/main/java/cn/lycode/transport/tui/TuiLayout.java`
- `lycode-transport-tui/src/main/java/cn/lycode/transport/tui/TuiRenderer.java`
- `lycode-transport-tui/src/main/java/cn/lycode/transport/tui/InlineViewport.java`
- `lycode-transport-tui/src/main/java/cn/lycode/transport/tui/InlineTerminalRenderer.java`
- `lycode-transport-tui/src/main/java/cn/lycode/transport/tui/TuiStartupBanner.java`
- `lycode-transport-tui/src/main/java/cn/lycode/transport/tui/TerminalCursorProbe.java`
- `lycode-transport-tui/src/main/java/cn/lycode/transport/tui/TerminalSession.java`
- `lycode-transport-tui/src/main/java/cn/lycode/transport/tui/TuiRenderState.java`
- `lycode-transport-tui/src/main/java/cn/lycode/transport/tui/TuiInputLoop.java`
- `lycode-transport-tui/src/main/java/cn/lycode/transport/tui/SlashCommandRouter.java`
- `lycode-transport-tui/src/main/java/cn/lycode/transport/tui/PermissionOverlay.java`
- `lycode-transport-tui/src/main/java/cn/lycode/transport/tui/DiffOverlay.java`
- `lycode-transport-tui/src/main/java/cn/lycode/transport/tui/SkillMentionParser.java`
- `lycode-transport-tui/src/main/java/cn/lycode/transport/tui/FileMentionPicker.java`

## Event Flow

1. `JLineTuiTransport.open()` enters normal-screen interactive modes, probes the cursor with a timeout and replays non-CPR input.
2. `RuntimeTuiSubmitHandler.submitUserInput()` routes slash commands and resolves Skill mentions.
3. Normal input becomes `TurnRequest` and executes asynchronously.
4. `TuiEventReducer.reduce()` projects message, tool, permission, retry, compact, interrupt and session events.
5. `TuiTranscriptPartitioner` splits the stable prefix from the live tail without storing durable state.
6. `TuiTranscriptCommitLedger` emits each stable block once per `(sessionId, leafId)` projection.
7. `TuiRenderer` renders new committed blocks separately from the bounded live/input/overlay/status surface.
8. On the first real-terminal frame, `InlineTerminalRenderer` prepends `TuiStartupBanner` as a one-time native-scrollback prelude.
9. `InlineTerminalRenderer` inserts committed lines above the viewport and diffs only the mutable surface in one synchronized terminal transaction.

## Invariants

- Reducers should not invent durable transcript content. They project events into display state.
- The first streaming, active, pending or running block starts the live tail; only the stable prefix can be committed.
- A stable block ID is committed at most once per projection key, including after transient stable/live regressions.
- Committed transcript is terminal-native scrollback. There is no application-side 500-line history window or scroll offset.
- The startup banner is terminal decoration, not transcript: render it once per transport before initial committed history, then preserve it across redraw, resize, projection changes and close.
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

- `lycode-transport-tui/src/test/java/cn/lycode/transport/tui/TerminalSessionTest.java`
- `lycode-transport-tui/src/test/java/cn/lycode/transport/tui/TerminalCursorProbeTest.java`
- `lycode-transport-tui/src/test/java/cn/lycode/transport/tui/InlineViewportTest.java`
- `lycode-transport-tui/src/test/java/cn/lycode/transport/tui/InlineTerminalRendererTest.java`
- `lycode-transport-tui/src/test/java/cn/lycode/transport/tui/TuiStartupBannerTest.java`
- `lycode-transport-tui/src/test/java/cn/lycode/transport/tui/TuiTranscriptPartitionerTest.java`
- `lycode-transport-tui/src/test/java/cn/lycode/transport/tui/TuiTranscriptCommitLedgerTest.java`
- `lycode-transport-tui/src/test/java/cn/lycode/transport/tui/TuiLayoutTest.java`
- `lycode-transport-tui/src/test/java/cn/lycode/transport/tui/TuiRendererTest.java`
- `lycode-transport-tui/src/test/java/cn/lycode/transport/tui/JLineTuiTransportTest.java`
- `lycode-transport-tui/src/test/java/cn/lycode/transport/tui/JLineTuiTransportConcurrencyTest.java`
- `lycode-transport-tui/src/test/java/cn/lycode/transport/tui/JLineTuiTransportRenderPipelineTest.java`
- `lycode-transport-tui/src/test/java/cn/lycode/transport/tui/RuntimeTuiSubmitHandlerTest.java`
- `lycode-transport-tui/src/test/java/cn/lycode/transport/tui/TuiEventReducerTest.java`
- `lycode-transport-tui/src/test/java/cn/lycode/transport/tui/PermissionOverlayTest.java`
- `lycode-transport-tui/src/test/java/cn/lycode/transport/tui/DiffOverlayTest.java`
- `lycode-transport-tui/src/test/java/cn/lycode/transport/tui/FileMentionPickerTest.java`
- `lycode-transport-tui/src/test/java/cn/lycode/transport/tui/SlashCommandRouterTest.java`
- `lycode-transport-tui/src/test/java/cn/lycode/transport/tui/SkillMentionParserTest.java`
- `lycode-transport-tui/src/test/java/cn/lycode/transport/tui/TuiContractEndToEndTest.java`
- `lycode-transport-tui/src/test/resources/run-tui-frame-pty.sh`
- `lycode-transport-tui/src/test/resources/run-tui-interaction-pty.sh`
- `lycode-transport-tui/src/test/resources/run-tui-smoke.sh`

## Before Editing

- Decide whether the change is view projection, rendering, input handling or runtime behavior.
- If it changes data shape, update `lycode-contracts` view model records first.
- Prefer event-driven state updates over querying internals from the UI layer.
- For rendering changes, test narrow width, multiline input, streaming finalization, resize reflow and surface-height bounds.
- For terminal lifecycle changes, test partial open failure and real tmux server/client PTYs before relying on control-sequence unit tests alone.
- For slash commands, test consumed, prompt-rewrite and state-change paths.

After using this Skill, reverse-check that the change did not move business state into TUI classes.
