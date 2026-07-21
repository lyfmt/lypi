package cn.lypi.contracts.runtime;

import cn.lypi.contracts.agent.SteeringMessage;
import java.util.Optional;

@FunctionalInterface
public interface AgentCommunicationPort {
    Optional<SteeringMessage> poll(String parentSessionId);

    static AgentCommunicationPort none() {
        return ignored -> Optional.empty();
    }
}
