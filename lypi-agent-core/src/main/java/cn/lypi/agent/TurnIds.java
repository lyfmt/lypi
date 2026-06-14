package cn.lypi.agent;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Queue;
import java.util.UUID;

public interface TurnIds {
    String newTurnId();

    String newMessageId();

    String newEntryId();

    static TurnIds random() {
        return new TurnIds() {
            @Override
            public String newTurnId() {
                return UUID.randomUUID().toString();
            }

            @Override
            public String newMessageId() {
                return UUID.randomUUID().toString();
            }

            @Override
            public String newEntryId() {
                return UUID.randomUUID().toString();
            }
        };
    }

    static TurnIds fixed(String... ids) {
        Queue<String> queue = new ArrayDeque<>(Arrays.asList(ids));
        return new TurnIds() {
            @Override
            public String newTurnId() {
                return next();
            }

            @Override
            public String newMessageId() {
                return next();
            }

            @Override
            public String newEntryId() {
                return next();
            }

            private String next() {
                if (queue.isEmpty()) {
                    throw new IllegalStateException("没有剩余的测试 ID");
                }
                return queue.remove();
            }
        };
    }
}
