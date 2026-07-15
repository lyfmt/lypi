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
- `lypi-transport-tui/src/main/java/cn/lypi/transport/tui/TuiContentLayout.java`
- `lypi-transport-tui/src/main/java/cn/lypi/transport/tui/TuiRenderer.java`
- `lypi-transport-tui/src/main/java/cn/lypi/transport/tui/TuiScreen.java`
- `lypi-transport-tui/src/main/java/cn/lypi/transport/tui/TerminalFrameRenderer.java`
- `lypi-transport-tui/src/main/java/cn/lypi/transport/tui/TerminalSession.java`
- `lypi-transport-tui/src/main/java/cn/lypi/transport/tui/TuiRenderState.java`
- `lypi-transport-tui/src/main/java/cn/lypi/transport/tui/TuiInputLoop.java`
- `lypi-transport-tui/src/main/java/cn/lypi/transport/tui/SlashCommandRouter.java`
- `lypi-transport-tui/src/main/java/cn/lypi/transport/tui/PermissionOverlay.java`
- `lypi-transport-tui/src/main/java/cn/lypi/transport/tui/DiffOverlay.java`
- `lypi-transport-tui/src/main/java/cn/lypi/transport/tui/SkillMentionParser.java`
- `lypi-transport-tui/src/main/java/cn/lypi/transport/tui/FileMentionPicker.java`

## Event Flow

1. `JLineTuiTransport.open()` enters the alternate screen and wires terminal IO, input, reducer and rendering.
2. `RuntimeTuiSubmitHandler.submitUserInput()` routes slash commands and resolves Skill mentions.
3. Normal input becomes `TurnRequest` and executes asynchronously.
4. `TuiEventReducer.reduce()` projects message, tool, permission, retry, compact, interrupt and session events.
5. `TuiTranscriptPartitioner` splits the stable prefix from the live tail without storing durable state.
6. `TuiRenderer` renders bounded history, live content and bottom chrome into one terminal-height frame.
7. `TerminalFrameRenderer` diffs physical rows by absolute screen position; it never sends transcript content to terminal scrollback.

## Invariants

- Reducers should not invent durable transcript content. They project events into display state.
- The stable transcript prefix becomes history. The first streaming, active, pending or running block starts the live tail.
- `TuiScreen` retains at most the latest 500 rendered history lines; session transcript and JSONL remain untouched.
- PageUp and PageDown scroll only history. Live content and bottom chrome stay on fixed rows.
- Historical tools always use their completed collapsed rendering. Ctrl+O affects tools in the live region only.
- Frames produced by `TuiRenderer` match terminal height. `TerminalFrameRenderer` rejects positive-height overflow instead of cropping it.
- First render and resize clear the alternate screen; normal redraws patch changed physical rows without `CRLF` scrolling.
- `TerminalSession` must pair DECSET/DECRST 1049 and restore terminal modes on close or partial open failure.
- Session id changes reset the history projection; resize reprojects the same session at the new width.
- Slash commands that change durable state should route through session/runtime contracts, not UI-only variables.
- Permission prompts display decision context and should clear on decision or interrupt.
- Skill mentions are resolved from the current `SkillIndex`, then passed into `TurnRequest`.
- Long-running work should not hold the UI lock while executing core logic.

## Tests To Check

- `lypi-transport-tui/src/test/java/cn/lypi/transport/tui/TerminalSessionTest.java`
- `lypi-transport-tui/src/test/java/cn/lypi/transport/tui/TerminalFrameRendererTest.java`
- `lypi-transport-tui/src/test/java/cn/lypi/transport/tui/TuiTranscriptPartitionerTest.java`
- `lypi-transport-tui/src/test/java/cn/lypi/transport/tui/TuiContentLayoutTest.java`
- `lypi-transport-tui/src/test/java/cn/lypi/transport/tui/TuiScreenTest.java`
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
- `lypi-transport-tui/src/test/resources/run-tui-smoke.sh`

## Before Editing

- Decide whether the change is view projection, rendering, input handling or runtime behavior.
- If it changes data shape, update `lypi-contracts` view model records first.
- Prefer event-driven state updates over querying internals from the UI layer.
- For rendering changes, test narrow width, multiline input, streaming updates, history/live allocation and terminal-height bounds.
- For terminal lifecycle changes, test partial open failure and a real PTY before relying on control-sequence unit tests alone.
- For slash commands, test consumed, prompt-rewrite and state-change paths.

After using this Skill, reverse-check that the change did not move business state into TUI classes.
