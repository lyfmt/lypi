package cn.lypi.transport.tui;

import cn.lypi.contracts.tui.StatusBarState;
import cn.lypi.contracts.tui.TuiViewModel;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

final class TuiInputLoop {
    private final TuiSubmitHandler submitHandler;
    private final FrameSink frameSink;
    private final TuiRenderer renderer;
    private TuiScreen screen;
    private TuiLayout layout;
    private final Supplier<TuiViewModel> viewSupplier;
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
        this(submitHandler, frameSink, renderer, screen, layout, null);
    }

    TuiInputLoop(
        TuiSubmitHandler submitHandler,
        FrameSink frameSink,
        TuiRenderer renderer,
        TuiScreen screen,
        TuiLayout layout,
        Supplier<TuiViewModel> viewSupplier
    ) {
        this.submitHandler = submitHandler;
        this.frameSink = frameSink;
        this.renderer = renderer;
        this.screen = screen;
        this.layout = layout;
        this.viewSupplier = viewSupplier == null ? this::emptyView : viewSupplier;
    }

    void acceptText(String text) {
        editor.insert(text);
        render();
    }

    void acceptPaste(String text) {
        editor.insertPaste(text);
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
            case DELETE_LINE_BEFORE_CURSOR -> editor.deleteLineBeforeCursor();
            case UNDO -> editor.undo();
            case YANK -> editor.yank();
            case YANK_POP -> editor.yankPop();
            case MOVE_LEFT -> editor.moveLeft();
            case MOVE_RIGHT -> editor.moveRight();
            case MOVE_WORD_LEFT -> editor.moveWordLeft();
            case MOVE_WORD_RIGHT -> editor.moveWordRight();
            case PREVIOUS_HISTORY -> editor.previousHistory();
            case NEXT_HISTORY -> editor.nextHistory();
            default -> {
            }
        }
        render();
    }

    String draft() {
        return editor.text();
    }

    void updateViewport(TuiScreen screen, TuiLayout layout) {
        this.screen = screen;
        this.layout = layout;
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
        editor.acceptHistoryEntry();
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
        frameSink.render(renderer.render(viewSupplier.get(), screen, layout, editor.text()));
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
