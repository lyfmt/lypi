package cn.lypi.transport.tui;

import cn.lypi.contracts.tui.PermissionPromptView;
import cn.lypi.contracts.tui.ResumeSessionController;
import cn.lypi.contracts.tui.SessionRuntimeState;
import cn.lypi.contracts.tui.StatusBarState;
import cn.lypi.contracts.tui.TuiViewModel;
import cn.lypi.contracts.skill.SkillDescriptor;
import cn.lypi.contracts.skill.SkillIndex;
import cn.lypi.contracts.skill.SkillMention;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
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
    private final Supplier<SkillIndex> skillIndexSupplier;
    private final Runnable immediateRender;
    private final ResumeSessionController resumeController;
    private final ResumeOverlayController resumeOverlayController;
    private SlashCommandPicker slashPicker;
    private SkillMentionToken skillToken;
    private int skillSelectedIndex;
    private final List<SkillMentionBinding> skillBindings = new java.util.ArrayList<>();
    private final SkillMentionSuppressions skillSuppressions = new SkillMentionSuppressions();
    private boolean slashOverlayClosed;
    private boolean interruptibleRunning;
    private boolean exitRequested;
    private boolean toolOutputExpanded;
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
        this(submitHandler, frameSink, renderer, screen, layout, viewSupplier, slashPickerSupplier, null);
    }

    TuiInputLoop(
        TuiSubmitHandler submitHandler,
        FrameSink frameSink,
        TuiRenderer renderer,
        TuiScreen screen,
        TuiLayout layout,
        Supplier<TuiViewModel> viewSupplier,
        Supplier<SlashCommandPicker> slashPickerSupplier,
        ResumeSessionController resumeController
    ) {
        this(submitHandler, frameSink, renderer, screen, layout, viewSupplier, slashPickerSupplier, resumeController, null);
    }

    TuiInputLoop(
        TuiSubmitHandler submitHandler,
        FrameSink frameSink,
        TuiRenderer renderer,
        TuiScreen screen,
        TuiLayout layout,
        Supplier<TuiViewModel> viewSupplier,
        Supplier<SlashCommandPicker> slashPickerSupplier,
        ResumeSessionController resumeController,
        Consumer<SessionRuntimeState> resumeStateConsumer
    ) {
        this(submitHandler, frameSink, renderer, screen, layout, viewSupplier, slashPickerSupplier, resumeController, resumeStateConsumer, null);
    }

    TuiInputLoop(
        TuiSubmitHandler submitHandler,
        FrameSink frameSink,
        TuiRenderer renderer,
        TuiScreen screen,
        TuiLayout layout,
        Supplier<TuiViewModel> viewSupplier,
        Supplier<SlashCommandPicker> slashPickerSupplier,
        ResumeSessionController resumeController,
        Consumer<SessionRuntimeState> resumeStateConsumer,
        Supplier<SkillIndex> skillIndexSupplier
    ) {
        this(
            submitHandler,
            frameSink,
            renderer,
            screen,
            layout,
            viewSupplier,
            slashPickerSupplier,
            resumeController,
            resumeStateConsumer,
            skillIndexSupplier,
            null
        );
    }

    TuiInputLoop(
        TuiSubmitHandler submitHandler,
        FrameSink frameSink,
        TuiRenderer renderer,
        TuiScreen screen,
        TuiLayout layout,
        Supplier<TuiViewModel> viewSupplier,
        Supplier<SlashCommandPicker> slashPickerSupplier,
        ResumeSessionController resumeController,
        Consumer<SessionRuntimeState> resumeStateConsumer,
        Supplier<SkillIndex> skillIndexSupplier,
        Runnable immediateRender
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
        this.skillIndexSupplier = skillIndexSupplier == null ? () -> new SkillIndex(List.of(), List.of()) : skillIndexSupplier;
        this.immediateRender = immediateRender;
        this.resumeController = resumeController;
        this.resumeOverlayController = resumeController == null ? null : new ResumeOverlayController(
            resumeController,
            resumeStateConsumer,
            submitHandler::resumeSession,
            editor::replaceDraft
        );
    }

    void acceptText(String text) {
        if (resumeOverlayController != null && resumeOverlayController.pendingBranchSummary()) {
            resumeOverlayController.handleText(text, this::render);
            return;
        }
        if (compactRunning()) {
            render();
            return;
        }
        if (resumeOverlayController != null) {
            resumeOverlayController.clearTransientLine();
        }
        editor.insert(text);
        slashOverlayClosed = false;
        skillToken = null;
        render();
    }

    void acceptPaste(String text) {
        if (compactRunning()) {
            render();
            return;
        }
        if (resumeOverlayController != null) {
            resumeOverlayController.clearTransientLine();
        }
        editor.insertPaste(text);
        slashOverlayClosed = false;
        skillToken = null;
        render();
    }

    void acceptKey(TerminalKey key) {
        if (compactRunning() && key != TerminalKey.CTRL_C && key != TerminalKey.ESC) {
            render();
            return;
        }
        if (resumeOverlayController != null) {
            resumeOverlayController.clearTransientLineUnlessPendingSummary();
        }
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
                submitHandler.requestInterrupt(key == TerminalKey.ESC ? "esc" : "ctrl-c");
                render();
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
        if (decision.action() == TerminalInputAction.INTERRUPT) {
            submitHandler.requestInterrupt(key == TerminalKey.ESC ? "esc" : "interrupt");
            render();
            return;
        }
        if (resumeOverlayOpen()) {
            handleResumeOverlayKey(key);
            return;
        }
        if (key == TerminalKey.ENTER && "/resume".equals(editor.text().trim()) && resumeController != null) {
            submitDraft();
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
        if (skillOverlayOpen() && !skillMatches().isEmpty()) {
            if (key == TerminalKey.ENTER || key == TerminalKey.TAB) {
                acceptSkillSelection();
                return;
            }
            if (key == TerminalKey.ESC) {
                skillSuppressions.suppress(skillToken);
                skillToken = null;
                render();
                return;
            }
            if (key == TerminalKey.UP) {
                moveSkillSelection(-1);
                render();
                return;
            }
            if (key == TerminalKey.DOWN) {
                moveSkillSelection(1);
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
        if (key == TerminalKey.UP && editor.canMoveVisualUp(layout.width())) {
            editor.moveVisualUp(layout.width());
            render();
            return;
        }
        if (key == TerminalKey.DOWN && editor.canMoveVisualDown(layout.width())) {
            editor.moveVisualDown(layout.width());
            render();
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
            case SCROLL_TRANSCRIPT_UP -> {
                if (transcriptScrollEnabled(prompt)) {
                    screen.scrollPageUp();
                }
            }
            case SCROLL_TRANSCRIPT_DOWN -> {
                if (transcriptScrollEnabled(prompt)) {
                    screen.scrollPageDown();
                }
            }
            case TOGGLE_TOOL_OUTPUT_EXPANDED, EXPAND_TOOLS -> toolOutputExpanded = !toolOutputExpanded;
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
        setInterruptibleRunning(toolRunning);
    }

    void setInterruptibleRunning(boolean interruptibleRunning) {
        this.interruptibleRunning = interruptibleRunning;
    }

    private void submitDraft() {
        String draft = editor.text();
        if (draft.isBlank()) {
            render();
            return;
        }
        if ("/resume".equals(draft.trim()) && resumeController != null) {
            openResumeSessions();
            editor.clear();
            slashOverlayClosed = true;
            render();
            return;
        }
        editor.acceptHistoryEntry();
        slashOverlayClosed = true;
        List<SkillMention> mentions = new SkillMentionParser(skillIndexSupplier.get().skills())
            .explicitMentions(draft, skillBindings, skillSuppressions);
        submitHandler.submitUserInput(draft, mentions);
        editor.clear();
        skillBindings.clear();
        skillSuppressions.clear();
        render();
    }

    private void handleCtrlC() {
        if (!editor.text().isBlank()) {
            editor.clear();
            slashOverlayClosed = true;
            skillBindings.clear();
            skillSuppressions.clear();
            render();
            return;
        }
        if (interruptibleRunning) {
            submitHandler.requestInterrupt("ctrl-c");
        } else {
            exitRequested = true;
            submitHandler.requestExit("ctrl-c");
        }
        render();
    }

    private TerminalInputContext inputContext(Optional<PermissionPromptView> prompt) {
        boolean runtimeInterruptible = interruptibleRunning || compactRunning();
        return new TerminalInputContext(
            editor.text(),
            runtimeInterruptible,
            prompt.isPresent(),
            prompt.isPresent() ? "permission" : "editor",
            "editor",
            prompt.map(PermissionPromptView::cancelOptionId).orElse("")
        );
    }

    private boolean transcriptScrollEnabled(Optional<PermissionPromptView> prompt) {
        return prompt.isEmpty() && !resumeOverlayOpen() && !slashOverlayOpen() && !skillOverlayOpen();
    }

    private void submitPermissionOption(PermissionPromptView prompt, String optionId) {
        submitHandler.submitPermissionOption(prompt.requestId(), prompt.toolUseId(), optionId);
        render();
    }

    void renderCurrentFrame() {
        renderFrame();
    }

    private void render() {
        if (immediateRender != null) {
            immediateRender.run();
            return;
        }
        renderFrame();
    }

    private void renderFrame() {
        frameSink.render(renderer.renderFrame(
            currentView(),
            screen,
            layout,
            editor.text(),
            editor.cursor(),
            overlayLines(),
            toolOutputExpanded
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

    private boolean compactRunning() {
        String runtimeLine = currentView().runtimeLine();
        return runtimeLine != null && runtimeLine.startsWith("compacting");
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
        return viewSupplier.get().permissionPrompt().isEmpty()
            && !resumeOverlayOpen()
            && !slashOverlayClosed
            && slashFilter().isPresent();
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
            slashPicker = slashPickerWithResumeCommand(slashPickerSupplier.get());
        }
        slashFilter().ifPresent(slashPicker::updateFilter);
        return slashPicker;
    }

    private SlashCommandPicker slashPickerWithResumeCommand(SlashCommandPicker picker) {
        if (resumeController == null) {
            return picker;
        }
        List<String> commands = new java.util.ArrayList<>(picker.visibleCommands());
        if (!commands.contains("/resume")) {
            commands.add("/resume");
        }
        return new SlashCommandPicker(commands);
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

    private List<String> overlayLines() {
        if (resumeOverlayController != null) {
            List<String> resumeLines = resumeOverlayController.overlayLines(layout.width());
            if (!resumeLines.isEmpty()) {
                return resumeLines;
            }
        }
        List<String> skillLines = skillOverlayLines();
        if (!skillLines.isEmpty()) {
            return skillLines;
        }
        return slashOverlayLines();
    }

    private boolean resumeOverlayOpen() {
        return resumeOverlayController != null && resumeOverlayController.open();
    }

    private void openResumeSessions() {
        resumeOverlayController.openSessions(currentView().statusBar().sessionId(), Math.max(1, layout.height() - 4));
    }

    private void handleResumeOverlayKey(TerminalKey key) {
        resumeOverlayController.handleKey(key, Math.max(1, layout.height() - 4), this::render);
    }

    private void acceptSlashSelection() {
        slashPicker().accept().ifPresent(command -> editor.replaceFirstToken(command + " "));
        slashOverlayClosed = true;
        render();
    }

    private boolean skillOverlayOpen() {
        if (viewSupplier.get().permissionPrompt().isPresent() || resumeOverlayOpen() || slashOverlayOpen()) {
            return false;
        }
        SkillMentionParser parser = new SkillMentionParser(skillIndexSupplier.get().skills());
        Optional<SkillMentionToken> token = parser.activeToken(editor.text(), editor.cursor());
        if (token.isEmpty() || skillSuppressions.suppressed(token.orElseThrow())) {
            skillToken = null;
            return false;
        }
        skillToken = token.orElseThrow();
        return !parser.matches(skillToken.prefix()).isEmpty();
    }

    private List<SkillDescriptor> skillMatches() {
        if (skillToken == null) {
            return List.of();
        }
        return new SkillMentionParser(skillIndexSupplier.get().skills()).matches(skillToken.prefix());
    }

    private List<String> skillOverlayLines() {
        if (!skillOverlayOpen()) {
            return List.of();
        }
        List<SkillDescriptor> matches = skillMatches();
        int selected = Math.max(0, Math.min(skillSelectedIndex, Math.max(0, matches.size() - 1)));
        int limit = Math.min(5, matches.size());
        int start = Math.max(0, selected - limit + 1);
        List<String> lines = new java.util.ArrayList<>();
        for (int index = start; index < start + limit; index++) {
            SkillDescriptor skill = matches.get(index);
            lines.add((index == selected ? "> $" : "  $") + skill.name() + "  " + skill.description());
        }
        return lines;
    }

    private void moveSkillSelection(int delta) {
        List<SkillDescriptor> matches = skillMatches();
        if (matches.isEmpty()) {
            return;
        }
        skillSelectedIndex = Math.max(0, Math.min(matches.size() - 1, skillSelectedIndex + delta));
    }

    private void acceptSkillSelection() {
        if (skillToken == null) {
            render();
            return;
        }
        List<SkillDescriptor> matches = skillMatches();
        if (matches.isEmpty()) {
            render();
            return;
        }
        SkillDescriptor skill = matches.get(Math.max(0, Math.min(skillSelectedIndex, matches.size() - 1)));
        String replacement = "$" + skill.name();
        editor.replaceRange(skillToken.start(), skillToken.end(), replacement);
        skillBindings.add(new SkillMentionBinding(
            skillToken.start(),
            skillToken.start() + replacement.length(),
            skill.name(),
            skill.skillFile()
        ));
        skillSuppressions.suppress(new SkillMentionToken(skillToken.start(), skillToken.start() + replacement.length(), skill.name()));
        skillToken = null;
        skillSelectedIndex = 0;
        render();
    }

    private TuiViewModel emptyView() {
        return new TuiViewModel(
            List.of(),
            new StatusBarState("", "", interruptibleRunning ? "running" : "ready", ""),
            List.of(),
            Optional.empty(),
            Optional.empty()
        );
    }

}
