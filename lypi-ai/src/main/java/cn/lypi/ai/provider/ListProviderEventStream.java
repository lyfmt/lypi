package cn.lypi.ai.provider;

import java.util.Iterator;
import java.util.List;

public final class ListProviderEventStream implements ProviderEventStream {
    private final List<ProviderRawEvent> events;

    public ListProviderEventStream(List<ProviderRawEvent> events) {
        this.events = List.copyOf(events);
    }

    @Override
    public Iterator<ProviderRawEvent> iterator() {
        return events.iterator();
    }

    @Override
    public void close() {
    }
}
