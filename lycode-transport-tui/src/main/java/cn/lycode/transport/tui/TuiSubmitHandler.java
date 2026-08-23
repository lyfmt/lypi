package cn.lycode.transport.tui;

import cn.lycode.contracts.agent.SteeringMessage;
import cn.lycode.contracts.skill.SkillMention;
import java.util.List;
import java.util.Optional;

interface TuiSubmitHandler {
    /**
     * 提交一轮用户输入。
     */
    void submitUserInput(String input);

    /**
     * 提交一轮用户输入，并携带显式 skill mention。
     */
    default void submitUserInput(String input, List<SkillMention> skillMentions) {
        submitUserInput(input);
    }

    /**
     * Submits temporary provider-login credentials without creating a user turn.
     */
    default void submitProviderLogin(String channelName, String baseUrl, String authKey) {
    }

    default List<SteeringMessage> pendingSteeringMessages() {
        return List.of();
    }

    default boolean hasPendingSteeringMessages() {
        return !pendingSteeringMessages().isEmpty();
    }

    default Optional<SteeringMessage> recallPendingSteering() {
        return Optional.empty();
    }

    /**
     * 请求中断当前活跃 turn。
     */
    void requestInterrupt(String reason);

    /**
     * 提交权限请求的用户选项。
     */
    default void submitPermissionOption(String requestId, String toolUseId, String optionId) {
    }

    /**
     * 请求退出当前 TUI 会话。
     */
    default void requestExit(String reason) {
    }

    /**
     * 恢复到指定 session leaf。
     */
    default void resumeSession(String sessionId, String leafId) {
    }
}
