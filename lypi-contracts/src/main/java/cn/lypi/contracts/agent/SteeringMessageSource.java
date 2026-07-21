package cn.lypi.contracts.agent;

import cn.lypi.contracts.common.SignalSubscription;
import java.util.Optional;

@FunctionalInterface
public interface SteeringMessageSource {
    SteeringMessageSource NONE = Optional::empty;

    /**
     * Returns the oldest pending message without blocking. Implementations must return empty instead of throwing.
     */
    Optional<SteeringMessage> poll();

    default boolean hasPending() {
        return false;
    }

    default SignalSubscription subscribe(Runnable listener) {
        if (hasPending()) {
            listener.run();
        }
        return SignalSubscription.none();
    }

    static SteeringMessageSource none() {
        return NONE;
    }
}
