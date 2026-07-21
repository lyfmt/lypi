package cn.lypi.contracts.agent;

import java.util.Optional;

@FunctionalInterface
public interface SteeringMessageSource {
    SteeringMessageSource NONE = Optional::empty;

    /**
     * Returns the oldest pending message without blocking. Implementations must return empty instead of throwing.
     */
    Optional<SteeringMessage> poll();

    static SteeringMessageSource none() {
        return NONE;
    }
}
