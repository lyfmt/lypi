package cn.lypi.ai.stream;

import cn.lypi.contracts.model.AssistantDone;
import cn.lypi.contracts.model.AssistantError;
import cn.lypi.contracts.model.AssistantEventStream;
import cn.lypi.contracts.model.AssistantStart;
import cn.lypi.contracts.model.AssistantStreamEvent;
import cn.lypi.contracts.model.AssistantStreamResult;
import cn.lypi.contracts.model.TokenUsage;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

public final class CompletedAssistantEventStream implements AssistantEventStream {
    private final List<AssistantStreamEvent> events;
    private final AssistantStreamResult result;

    public CompletedAssistantEventStream(List<AssistantStreamEvent> events, AssistantStreamResult result) {
        this.events = List.copyOf(events);
        this.result = result;
    }

    public static CompletedAssistantEventStream completed(List<AssistantStreamEvent> events) {
        List<AssistantStreamEvent> eventSnapshot = List.copyOf(events);
        String messageId = eventSnapshot.stream()
            .filter(AssistantStart.class::isInstance)
            .map(AssistantStart.class::cast)
            .map(AssistantStart::messageId)
            .findFirst()
            .orElse("");
        Optional<TokenUsage> usage = eventSnapshot.stream()
            .filter(AssistantDone.class::isInstance)
            .map(AssistantDone.class::cast)
            .flatMap(done -> done.usage().stream())
            .findFirst();
        Optional<String> stopReason = eventSnapshot.stream()
            .filter(AssistantDone.class::isInstance)
            .map(AssistantDone.class::cast)
            .flatMap(done -> done.stopReason().stream())
            .findFirst();
        Optional<AssistantError> error = eventSnapshot.stream()
            .filter(AssistantError.class::isInstance)
            .map(AssistantError.class::cast)
            .findFirst();
        boolean completed = eventSnapshot.stream().anyMatch(AssistantDone.class::isInstance);
        return new CompletedAssistantEventStream(
            eventSnapshot,
            new AssistantStreamResult(messageId, eventSnapshot, usage, stopReason, completed, false, error)
        );
    }

    public static CompletedAssistantEventStream aborted() {
        return new CompletedAssistantEventStream(
            List.of(),
            new AssistantStreamResult("", List.of(), Optional.empty(), Optional.empty(), false, true, Optional.empty())
        );
    }

    @Override
    public Iterator<AssistantStreamEvent> iterator() {
        return events.iterator();
    }

    @Override
    public AssistantStreamResult result() {
        return result;
    }

    @Override
    public void close() {
    }
}
