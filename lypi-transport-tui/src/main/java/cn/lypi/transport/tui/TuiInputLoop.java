package cn.lypi.transport.tui;

import cn.lypi.contracts.tui.PermissionPromptView;
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
    private final TerminalInputPolicy inputPolicy = new TerminalInputPolicy();
    private final Supplier<SlashCommandPicker> slashPickerSupplier;
    private SlashCommandPicker slashPicker;
    private boolean slashOverlayClosed;
    private boolean toolRunning;
    private boolean exitRequested;
    private String permissionRequestId = "";
    private String selectedPermissionOptionId = "";

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
        this(submitHandler, frameSink, renderer, screen, layout, viewSupplier, null);
    }

    TuiInputLoop(
        TuiSubmitHandler submitHandler,
        FrameSink frameSink,
        TuiRenderer renderer,
        TuiScreen screen,
        TuiLayout layout,
        Supplier<TuiViewModel> viewSupplier,
        Supplier<SlashCommandPicker> slashPickerSupplier
    ) {
        this.submitHandler = submitHandler;
        this.frameSink = frameSink;
        this.renderer = renderer;
        this.screen = screen;
        this.layout = layout;
        this.viewSupplier = viewSupplier == null ? this::emptyView : viewSupplier;
        this.slashPickerSupplier = slashPickerSupplier == null
            ? () -> SlashCommandPicker.withTemplates(List.of())
            : slashPickerSupplier;
    }

    void acceptText(String text) {
        editor.insert(text);
        slashOverlayClosed = false;
        render();
    }

    void acceptPaste(String text) {
        editor.insertPaste(text);
        slashOverlayClosed = false;
        render();
    }

    void acceptKey(TerminalKey key) {
        Optional<PermissionPromptView> prompt = currentView().permissionPrompt();
        if (prompt.isPresent()) {
            PermissionPromptView currentPrompt = prompt.orElseThrow();
            if (key == TerminalKey.UP) {
                movePermissionSelection(currentPrompt, -1);
                render();
                return;
            }
            if (key == TerminalKey.DOWN) {
                movePermissionSelection(currentPrompt, 1);
                render();
                return;
            }
            if (key == TerminalKey.ENTER) {
                submitPermissionOption(currentPrompt, currentPrompt.selectedOptionId());
                return;
            }
            if (key == TerminalKey.ESC || key == TerminalKey.CTRL_C) {
                submitPermissionOption(currentPrompt, currentPrompt.cancelOptionId());
                return;
            }
        }
        TerminalInputDecision decision = inputPolicy.decide(key, inputContext(prompt));
        if (decision.action() == TerminalInputAction.SUBMIT_PERMISSION_OPTION) {
            prompt.ifPresent(value -> submitPermissionOption(value, decision.optionId().orElse("")));
            if (prompt.isEmpty()) {
                render();
            }
            return;
        }
        if (slashOverlayOpen() && !slashPicker().visibleCommands().isEmpty()) {
            if (key == TerminalKey.ENTER || key == TerminalKey.TAB) {
                acceptSlashSelection();
                return;
            }
            if (key == TerminalKey.ESC) {
                slashOverlayClosed = true;
                render();
                return;
            }
            if (key == TerminalKey.UP) {
                slashPicker().moveUp();
                render();
                return;
            }
            if (key == TerminalKey.DOWN) {
                slashPicker().moveDown();
                render();
                return;
            }
        }
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
            case DELETE_PREVIOUS_CHARACTER -> editor.deletePreviousCharacter();
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

    int cursor() {
        return editor.cursor();
    }

    boolean exitRequested() {
        return exitRequested;
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
        slashOverlayClosed = true;
        submitHandler.submitUserInput(draft);
        editor.clear();
        render();
    }

    private void handleCtrlC() {
        if (!editor.text().isBlank()) {
            editor.clear();
            slashOverlayClosed = true;
            render();
            return;
        }
        if (toolRunning) {
            submitHandler.requestInterrupt("ctrl-c");
        } else {
            exitRequested = true;
            submitHandler.requestExit("ctrl-c");
        }
        render();
    }

    private TerminalInputContext inputContext(Optional<PermissionPromptView> prompt) {
        return new TerminalInputContext(
            editor.text(),
            toolRunning,
            prompt.isPresent(),
            prompt.isPresent() ? "permission" : "editor",
            "editor",
            prompt.map(PermissionPromptView::cancelOptionId).orElse("")
        );
    }

    private void submitPermissionOption(PermissionPromptView prompt, String optionId) {
        submitHandler.submitPermissionOption(prompt.requestId(), prompt.toolUseId(), optionId);
        render();
    }

    private void render() {
        frameSink.render(renderer.render(
            currentView(),
            screen,
            layout,
            editor.text(),
            editor.cursor(),
            slashOverlayLines()
        ));
    }

    private TuiViewModel currentView() {
        TuiViewModel view = viewSupplier.get();
        Optional<PermissionPromptView> prompt = view.permissionPrompt();
        syncPermissionSelection(prompt);
        return new TuiViewModel(
            view.blocks(),
            view.statusBar(),
            view.runtimeLine(),
            view.files(),
            prompt.map(this::withSelectedPermissionOption),
            view.diffView()
        );
    }

    private void syncPermissionSelection(Optional<PermissionPromptView> prompt) {
        if (prompt.isEmpty()) {
            permissionRequestId = "";
            selectedPermissionOptionId = "";
            return;
        }
        PermissionPromptView currentPrompt = prompt.orElseThrow();
        if (!currentPrompt.requestId().equals(permissionRequestId)) {
            permissionRequestId = currentPrompt.requestId();
            selectedPermissionOptionId = currentPrompt.selectedOptionId();
            return;
        }
        if (!hasOptionId(currentPrompt, selectedPermissionOptionId)) {
            selectedPermissionOptionId = currentPrompt.selectedOptionId();
        }
    }

    private PermissionPromptView withSelectedPermissionOption(PermissionPromptView prompt) {
        return new PermissionPromptView(
            prompt.requestId(),
            prompt.toolUseId(),
            prompt.reason(),
            prompt.rule(),
            prompt.defaultOptionId(),
            prompt.cancelOptionId(),
            prompt.options(),
            selectedPermissionOptionId
        );
    }

    private void movePermissionSelection(PermissionPromptView prompt, int delta) {
        if (prompt.options().isEmpty()) {
            return;
        }
        int currentIndex = selectedOptionIndex(prompt);
        int nextIndex = Math.max(0, Math.min(prompt.options().size() - 1, currentIndex + delta));
        selectedPermissionOptionId = prompt.options().get(nextIndex).optionId();
    }

    private int selectedOptionIndex(PermissionPromptView prompt) {
        for (int index = 0; index < prompt.options().size(); index++) {
            if (prompt.options().get(index).optionId().equals(selectedPermissionOptionId)) {
                return index;
            }
        }
        return 0;
    }

    private boolean hasOptionId(PermissionPromptView prompt, String optionId) {
        if (prompt.options().isEmpty()) {
            return optionId != null && !optionId.isBlank();
        }
        return prompt.options().stream().anyMatch(option -> option.optionId().equals(optionId));
    }

    private boolean slashOverlayOpen() {
        return viewSupplier.get().permissionPrompt().isEmpty() && !slashOverlayClosed && slashFilter().isPresent();
    }

    private Optional<String> slashFilter() {
        String draft = editor.text();
        int cursor = editor.cursor();
        if (cursor < 1 || draft.isEmpty() || draft.charAt(0) != '/') {
            return Optional.empty();
        }
        int firstTokenEnd = firstTokenEnd(draft);
        if (cursor > firstTokenEnd) {
            return Optional.empty();
        }
        return Optional.of(draft.substring(0, cursor));
    }

    private int firstTokenEnd(String draft) {
        int index = 0;
        while (index < draft.length() && !Character.isWhitespace(draft.charAt(index))) {
            index++;
        }
        return index;
    }

    private SlashCommandPicker slashPicker() {
        if (slashPicker == null) {
            slashPicker = slashPickerSupplier.get();
        }
        slashFilter().ifPresent(slashPicker::updateFilter);
        return slashPicker;
    }

    private List<String> slashOverlayLines() {
        if (!slashOverlayOpen()) {
            return List.of();
        }
        SlashCommandPicker picker = slashPicker();
        List<String> visible = picker.visibleCommands();
        int selected = Math.max(0, Math.min(picker.selectedIndex(), Math.max(0, visible.size() - 1)));
        int limit = Math.min(5, visible.size());
        int start = Math.max(0, selected - limit + 1);
        List<String> lines = new java.util.ArrayList<>();
        for (int index = start; index < start + limit; index++) {
            lines.add((index == selected ? "> " : "  ") + visible.get(index));
        }
        return lines;
    }

    private void acceptSlashSelection() {
        slashPicker().accept().ifPresent(command -> editor.replaceFirstToken(command + " "));
        slashOverlayClosed = true;
        render();
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
