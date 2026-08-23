package cn.lycode.boot.tool;

import cn.lycode.contracts.event.EventBus;
import cn.lycode.contracts.event.EventFilter;
import cn.lycode.contracts.event.PermissionRequestEvent;
import cn.lycode.contracts.event.PermissionResponseEvent;
import cn.lycode.contracts.security.PermissionResponse;
import cn.lycode.tool.PermissionResponseGate;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 将 transport 发布的权限响应事件桥接为工具层等待的同步响应。
 */
final class EventBusPermissionResponseGate implements PermissionResponseGate {
    private final ConcurrentHashMap<ResponseKey, LinkedBlockingQueue<PermissionResponseEvent>> responses = new ConcurrentHashMap<>();

    EventBusPermissionResponseGate(EventBus eventBus) {
        Objects.requireNonNull(eventBus, "eventBus must not be null")
            .subscribe(new EventFilter(Optional.empty(), Optional.of(PermissionResponseEvent.class)), envelope -> {
                PermissionResponseEvent event = (PermissionResponseEvent) envelope.event();
                responses.computeIfAbsent(ResponseKey.from(event), ignored -> new LinkedBlockingQueue<>()).offer(event);
            });
    }

    @Override
    public PermissionResponse request(PermissionRequestEvent requestEvent) {
        ResponseKey key = ResponseKey.from(requestEvent);
        LinkedBlockingQueue<PermissionResponseEvent> queue = responses.computeIfAbsent(key, ignored -> new LinkedBlockingQueue<>());
        try {
            PermissionResponseEvent event = queue.take();
            return new PermissionResponse(
                event.sessionId(),
                event.requestId(),
                event.selectedOptionId(),
                event.fromKeyboardCancel(),
                event.timestamp()
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new PermissionResponse(
                requestEvent.sessionId(),
                requestEvent.requestId(),
                requestEvent.cancelOptionId(),
                true,
                Instant.now()
            );
        } finally {
            if (queue.isEmpty()) {
                responses.remove(key, queue);
            }
        }
    }

    private record ResponseKey(String sessionId, String requestId) {
        private static ResponseKey from(PermissionRequestEvent event) {
            return new ResponseKey(event.sessionId(), event.requestId());
        }

        private static ResponseKey from(PermissionResponseEvent event) {
            return new ResponseKey(event.sessionId(), event.requestId());
        }
    }
}
