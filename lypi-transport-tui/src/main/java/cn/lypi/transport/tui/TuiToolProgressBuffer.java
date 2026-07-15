package cn.lypi.transport.tui;

import cn.lypi.contracts.common.ToolProgress;
import cn.lypi.contracts.common.ToolProgressKind;
import cn.lypi.contracts.event.ToolEndEvent;
import cn.lypi.contracts.tool.ToolOutputRef;
import cn.lypi.contracts.tool.ToolResultSummary;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class TuiToolProgressBuffer {
    static final int MAX_RETAINED_CHARACTERS = 16 * 1024;
    static final int MAX_RETAINED_LINES = 200;

    private static final int MAX_INITIAL_LINES = 7;
    private static final int MAX_STATE_LINES = 5;
    private static final int MAX_FINAL_LINES = 4;
    private static final int MAX_OUTPUT_LINES = MAX_RETAINED_LINES
        - MAX_INITIAL_LINES
        - MAX_STATE_LINES
        - MAX_FINAL_LINES;
    private static final int MAX_INITIAL_CHARACTERS = 200;
    private static final int MAX_STATE_CHARACTERS = 350;
    private static final int MAX_FINAL_CHARACTERS = 250;
    private static final int MAX_STREAM_LABEL_CHARACTERS = 48;

    private final ArrayDeque<String> completedOutputLines = new ArrayDeque<>();
    private final StringBuilder currentOutputLine = new StringBuilder();
    private final EnumMap<ToolProgressKind, String> latestStates = new EnumMap<>(ToolProgressKind.class);
    private final List<String> initialLines;
    private final List<String> finalLines = new ArrayList<>();
    private String currentStream;
    private String pendingCarriageReturnStream;
    private int completedOutputCharacters;
    private long omittedCharacters;
    private long omittedLines;
    private boolean active = true;

    TuiToolProgressBuffer() {
        this("");
    }

    TuiToolProgressBuffer(String initialDetail) {
        initialLines = boundedLines(initialDetail, MAX_INITIAL_LINES, MAX_INITIAL_CHARACTERS);
    }

    void append(ToolProgress progress) {
        if (!active || progress == null) {
            return;
        }
        if (progress.kind() == ToolProgressKind.OUTPUT) {
            appendOutput(progress.stream(), progress.delta());
            return;
        }
        String detail = formatState(progress);
        if (detail.isBlank()) {
            latestStates.remove(progress.kind());
        } else {
            latestStates.put(progress.kind(), detail);
        }
    }

    void complete(ToolEndEvent event) {
        if (!active) {
            return;
        }
        if (event != null) {
            if (event.exitCode() != null) {
                finalLines.add("exit " + event.exitCode());
            }
            if (event.status() != null) {
                finalLines.add("status " + event.status().name().toLowerCase(Locale.ROOT));
            }
            ToolResultSummary summary = event.resultSummary();
            if (summary != null) {
                addFinalLine(firstNonBlank(summary.summary(), summary.title()));
            }
            addFinalLine(preview(event.resultRef(), summary));
        }
        while (finalLines.size() > MAX_FINAL_LINES) {
            finalLines.remove(MAX_FINAL_LINES);
        }
        active = false;
    }

    boolean active() {
        return active;
    }

    int retainedCharacters() {
        int lines = retainedLineCount();
        int currentCharacters = currentStream == null
            ? 0
            : outputPrefix(currentStream).length() + currentOutputLine.length();
        return completedOutputCharacters + currentCharacters + Math.max(0, lines - 1);
    }

    int retainedLineCount() {
        return completedOutputLines.size() + (currentStream == null ? 0 : 1);
    }

    String render() {
        List<String> groups = new ArrayList<>(5);
        addGroup(groups, boundedGroup(initialLines, MAX_INITIAL_CHARACTERS));
        if (omittedCharacters > 0 || omittedLines > 0) {
            groups.add("... earlier output omitted (" + omittedCharacters + " characters, "
                + omittedLines + " lines) ...");
        }
        addGroup(groups, renderOutput());
        addGroup(groups, boundedGroup(stateLines(), MAX_STATE_CHARACTERS));
        addGroup(groups, boundedGroup(finalLines, MAX_FINAL_CHARACTERS));
        return String.join("\n", groups);
    }

    private void appendOutput(String stream, String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        String safeStream = streamLabel(stream);
        for (int index = 0; index < delta.length(); index++) {
            char character = delta.charAt(index);
            if (pendingCarriageReturnStream != null) {
                boolean completesCrLf = character == '\n'
                    && pendingCarriageReturnStream.equals(safeStream);
                pendingCarriageReturnStream = null;
                if (completesCrLf) {
                    continue;
                }
            }
            ensureCurrentStream(safeStream);
            if (character == '\r') {
                completeCurrentLine();
                pendingCarriageReturnStream = safeStream;
            } else if (character == '\n') {
                completeCurrentLine();
            } else {
                currentOutputLine.append(character);
            }
        }
        trimOutput();
    }

    private void ensureCurrentStream(String stream) {
        if (currentStream == null) {
            currentStream = stream;
            return;
        }
        if (!currentStream.equals(stream)) {
            completeCurrentLine();
            currentStream = stream;
        }
    }

    private void completeCurrentLine() {
        if (currentStream == null) {
            return;
        }
        String line = outputPrefix(currentStream) + currentOutputLine;
        completedOutputLines.addLast(line);
        completedOutputCharacters += line.length();
        currentOutputLine.setLength(0);
        currentStream = null;
        trimOutput();
    }

    private void trimOutput() {
        while (retainedLineCount() > outputLineLimit() || retainedCharacters() > MAX_RETAINED_CHARACTERS) {
            if (!completedOutputLines.isEmpty()) {
                String omitted = completedOutputLines.removeFirst();
                completedOutputCharacters -= omitted.length();
                omittedCharacters += omitted.length() + 1L;
                omittedLines++;
                continue;
            }
            int excess = retainedCharacters() - MAX_RETAINED_CHARACTERS;
            if (excess <= 0 || currentOutputLine.isEmpty()) {
                break;
            }
            int charactersToOmit = Math.min(excess, currentOutputLine.length());
            currentOutputLine.delete(0, charactersToOmit);
            omittedCharacters += charactersToOmit;
        }
    }

    private int outputLineLimit() {
        return omittedCharacters > 0 || omittedLines > 0 ? MAX_OUTPUT_LINES - 1 : MAX_OUTPUT_LINES;
    }

    private String renderOutput() {
        if (completedOutputLines.isEmpty() && currentStream == null) {
            return "";
        }
        StringBuilder output = new StringBuilder(retainedCharacters());
        for (String line : completedOutputLines) {
            appendLine(output, line);
        }
        if (currentStream != null) {
            appendLine(output, outputPrefix(currentStream) + currentOutputLine);
        }
        return output.toString();
    }

    private List<String> stateLines() {
        List<String> lines = new ArrayList<>(MAX_STATE_LINES);
        addStateLine(lines, ToolProgressKind.PHASE);
        addStateLine(lines, ToolProgressKind.STATUS);
        addStateLine(lines, ToolProgressKind.COUNTER);
        addStateLine(lines, ToolProgressKind.PERCENT);
        addStateLine(lines, ToolProgressKind.CUSTOM);
        return lines;
    }

    private void addStateLine(List<String> lines, ToolProgressKind kind) {
        String line = latestStates.get(kind);
        if (line != null && !line.isBlank()) {
            lines.add(line);
        }
    }

    private String formatState(ToolProgress progress) {
        return switch (progress.kind()) {
            case PHASE -> firstNonBlank(progress.phase(), progress.title(), progress.detail());
            case STATUS -> firstNonBlank(progress.title(), "") + suffix(progress.detail());
            case COUNTER -> firstNonBlank(progress.title(), "progress") + " "
                + valueOrEmpty(progress.current()) + "/" + valueOrEmpty(progress.total());
            case PERCENT -> firstNonBlank(progress.title(), "progress") + " " + percentLabel(progress.percent());
            case CUSTOM -> firstNonBlank(progress.title(), progress.metadata().toString());
            case OUTPUT -> "";
        };
    }

    private void addFinalLine(String line) {
        String safeLine = singleLine(line);
        if (!safeLine.isBlank() && finalLines.size() < MAX_FINAL_LINES) {
            finalLines.add(safeLine);
        }
    }

    private String preview(ToolOutputRef resultRef, ToolResultSummary summary) {
        String refPreview = metadataString(resultRef == null ? null : resultRef.metadata(), "preview");
        if (!refPreview.isBlank()) {
            return refPreview;
        }
        return summary == null ? "" : metadataString(summary.metadata(), "preview");
    }

    private String streamLabel(String stream) {
        String label = singleLine(firstNonBlank(stream, "output"));
        if (label.length() <= MAX_STREAM_LABEL_CHARACTERS) {
            return label;
        }
        return label.substring(0, MAX_STREAM_LABEL_CHARACTERS);
    }

    private String outputPrefix(String stream) {
        return stream + ": ";
    }

    private String percentLabel(Double percent) {
        if (percent == null) {
            return "";
        }
        if (percent % 1 == 0) {
            return percent.intValue() + "%";
        }
        return String.format(Locale.ROOT, "%.1f%%", percent);
    }

    private List<String> boundedLines(String detail, int maxLines, int maxCharacters) {
        if (detail == null || detail.isBlank()) {
            return List.of();
        }
        String normalized = detail.replace("\r\n", "\n").replace('\r', '\n');
        String[] candidates = normalized.split("\n", -1);
        List<String> lines = new ArrayList<>(Math.min(candidates.length, maxLines));
        int characters = 0;
        for (String candidate : candidates) {
            if (lines.size() >= maxLines || characters >= maxCharacters) {
                break;
            }
            int separator = lines.isEmpty() ? 0 : 1;
            int remaining = maxCharacters - characters - separator;
            if (remaining <= 0) {
                break;
            }
            String line = candidate.length() <= remaining ? candidate : candidate.substring(0, remaining);
            lines.add(line);
            characters += separator + line.length();
        }
        return List.copyOf(lines);
    }

    private String boundedGroup(List<String> lines, int maxCharacters) {
        if (lines.isEmpty()) {
            return "";
        }
        String group = String.join("\n", lines);
        return group.length() <= maxCharacters ? group : group.substring(0, maxCharacters);
    }

    private String metadataString(Map<String, Object> metadata, String key) {
        if (metadata == null || metadata.get(key) == null) {
            return "";
        }
        return metadata.get(key).toString();
    }

    private String singleLine(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\r', ' ').replace('\n', ' ').strip();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String suffix(String value) {
        return value == null || value.isBlank() ? "" : " " + value;
    }

    private String valueOrEmpty(Long value) {
        return value == null ? "" : value.toString();
    }

    private void addGroup(List<String> groups, String group) {
        if (group != null && !group.isBlank()) {
            groups.add(group);
        }
    }

    private void appendLine(StringBuilder builder, String line) {
        if (!builder.isEmpty()) {
            builder.append('\n');
        }
        builder.append(line);
    }
}
