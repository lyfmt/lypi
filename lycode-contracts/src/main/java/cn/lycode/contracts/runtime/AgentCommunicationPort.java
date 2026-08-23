package cn.lycode.contracts.runtime;

import cn.lycode.contracts.agent.SteeringMessage;
import java.util.Optional;

@FunctionalInterface
public interface AgentCommunicationPort {
    Optional<SteeringMessage> poll(String parentSessionId);

    static AgentCommunicationPort none() {
        return ignored -> Optional.empty();
    }
}
