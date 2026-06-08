package cn.lypi.transport.tui;

import cn.lypi.contracts.tui.StatusBarState;
import cn.lypi.contracts.tui.TuiViewModel;
import java.util.List;
import java.util.Optional;

final class TuiInputLoop {
    private final TuiSubmitHandler submitHandler;
    private final FrameSink frameSink;
    private final TuiRenderer renderer;
    private final TuiScreen screen;
    private final TuiLayout layout;
    private final InputEditor editor = new InputEditor();
    private final KeyBindingRegistry bindings = KeyBindingRegistry.defaults();
    private boolean toolRunning;

    TuiInputLoop(
        TuiSubmitHandler submitHandler,
        FrameSink frameSink,
        TuiRenderer renderer,
        TuiScreen screen,
        TuiLayout layout
    ) {
        this.submitHandler = submitHandler;
        this.frameSink = frameSink;
        this.renderer = renderer;
        this.screen = screen;
        this.layout = layout;
    }

    void acceptText(String text) {
        editor.insert(text);
        render();
    }

    void acceptKey(TerminalKey key) {
        if (key == TerminalKey.ENTER) {
            submitDraft();
            return;
        }
        if (key == TerminalKey.CTRL_C) {
            handleCtrlC();
            return;
        }
        TerminalInputAction action = bindings.actionFor(key);
        switch (action) {
            case INSERT_NEWLINE -> editor.insertNewline();
            case DELETE_PREVIOUS_WORD -> editor.deletePreviousWord();
            case DELETE_NEXT_WORD -> editor.deleteNextWord();
            case MOVE_WORD_LEFT -> editor.moveWordLeft();
            case MOVE_WORD_RIGHT -> editor.moveWordRight();
            default -> {
            }
        }
        render();
    }

    String draft() {
        return editor.text();
    }

    void setToolRunning(boolean toolRunning) {
        this.toolRunning = toolRunning;
    }

    private void submitDraft() {
        String draft = editor.text();
        if (draft.isBlank()) {
            render();
            return;
        }
        submitHandler.submitUserInput(draft);
        editor.clear();
        render();
    }

    private void handleCtrlC() {
        if (!editor.text().isBlank()) {
            editor.clear();
            render();
            return;
        }
        if (toolRunning) {
            submitHandler.requestInterrupt("ctrl-c");
        }
        render();
    }

    private void render() {
        frameSink.render(renderer.render(emptyView(), screen, layout, editor.text()));
    }

    private TuiViewModel emptyView() {
        return new TuiViewModel(
            List.of(),
            new StatusBarState("", "", toolRunning ? "running" : "ready", ""),
            List.of(),
            Optional.empty(),
            Optional.empty()
        );
    }
}
