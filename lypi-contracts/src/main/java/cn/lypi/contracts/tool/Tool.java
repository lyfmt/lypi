package cn.lypi.contracts.tool;

import cn.lypi.contracts.common.JsonSchema;
import cn.lypi.contracts.common.ProgressSink;
import cn.lypi.contracts.common.ValidationResult;
import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.security.PermissionDecision;
import java.util.List;

public interface Tool<I, O> {
    /**
     * 返回工具主名称。
     *
     * 工具名用于模型 tool call、注册表查找和审计关联。
     */
    String name();

    /**
     * 返回模型可见的工具说明。
     *
     * NOTE: 默认使用工具名，具体工具可覆盖为更完整的 provider tool description。
     */
    default String description() {
        return name();
    }

    /**
     * 返回工具别名。
     *
     * NOTE: 别名只用于解析，不应替代审计中的主工具名。
     */
    List<String> aliases();

    /**
     * 返回工具输入 JSON Schema。
     *
     * 模型调用工具前，ToolOrchestrator 使用该 schema 进行基础入参校验。
     */
    JsonSchema inputSchema();

    /**
     * 校验工具输入。
     *
     * NOTE: 负责 schema 之外的语义校验，校验失败应回灌为 tool result error。
     */
    ValidationResult validateInput(I input, ToolUseContext context);

    /**
     * 执行工具级权限检查。
     *
     * NOTE: 权限决策必须可解释，并与全局 PolicyEngine 决策合并。
     */
    PermissionDecision checkPermissions(I input, ToolUseContext context);

    /**
     * 执行工具。
     *
     * NOTE: 工具执行不得直接写 session，结果由运行时统一转换为事件和 entry。
     */
    ToolResult<O> execute(I input, ToolUseContext context, ProgressSink progress);

    /**
     * 返回工具中断行为。
     *
     * 用于区分可取消工具和必须阻塞等待完成的工具。
     */
    InterruptBehavior interruptBehavior();

    /**
     * 判断工具调用是否只读。
     *
     * 只读结果可用于并发计划和权限默认策略。
     */
    boolean isReadOnly(I input);

    /**
     * 判断工具调用是否可安全并发。
     *
     * NOTE: 可并发只表示运行时调度许可，不表示绕过权限或审计。
     */
    boolean isConcurrencySafe(I input);

    /**
     * 判断工具调用是否具有破坏性。
     *
     * 用于权限提示、风险展示和执行排序。
     */
    boolean isDestructive(I input);

    /**
     * 返回工具结果最大上下文预算。
     *
     * NOTE: 超出预算的结果应持久化并用预览替换。
     */
    int maxResultSize();

    /**
     * 渲染给用户查看的工具调用摘要。
     *
     * NOTE: 该文本用于 TUI 或 headless 展示，不等同于模型上下文序列化。
     */
    String renderForUser(I input);

    /**
     * 将工具输出序列化为模型上下文消息。
     *
     * NOTE: 输出应遵守预算、脱敏和 tool result 语义。
     */
    AgentMessage serializeForContext(O output);
}
