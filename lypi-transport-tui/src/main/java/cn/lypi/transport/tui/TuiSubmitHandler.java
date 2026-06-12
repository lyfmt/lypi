package cn.lypi.transport.tui;

import cn.lypi.contracts.skill.SkillMention;
import java.util.List;

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
