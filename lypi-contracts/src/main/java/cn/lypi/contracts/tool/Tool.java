package cn.lypi.contracts.tool;

import cn.lypi.contracts.common.JsonSchema;
import cn.lypi.contracts.common.ProgressSink;
import cn.lypi.contracts.common.ValidationResult;
import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.security.PermissionDecision;
import java.util.List;

public interface Tool<I, O> {
    /*
    * @status : 未完成
    * @summary : 返回工具主名称。
    *@description : 工具名用于模型 tool call、注册表查找和审计关联。
    *
    *
                              */
    String name();

    /*
    * @status : 未完成
    * @summary : 返回工具别名。
    *@description : 别名只用于解析，不应替代审计中的主工具名。
    *
    *
                              */
    List<String> aliases();

    /*
    * @status : 未完成
    * @summary : 返回工具输入 JSON Schema。
    *@description : 模型调用工具前，ToolOrchestrator 使用该 schema 进行基础入参校验。
    *
    *
                              */
    JsonSchema inputSchema();

    /*
    * @status : 未完成
    * @summary : 校验工具输入。
    *@description : 负责 schema 之外的语义校验，校验失败应回灌为 tool result error。
    *
    *
                              */
    ValidationResult validateInput(I input, ToolUseContext context);

    /*
    * @status : 未完成
    * @summary : 执行工具级权限检查。
    *@description : 权限决策必须可解释，并与全局 PolicyEngine 决策合并。
    *
    *
                              */
    PermissionDecision checkPermissions(I input, ToolUseContext context);

    /*
    * @status : 未完成
    * @summary : 执行工具。
    *@description : 工具执行不得直接写 session，结果由运行时统一转换为事件和 entry。
    *
    *
                              */
    ToolResult<O> execute(I input, ToolUseContext context, ProgressSink progress);

    /*
    * @status : 未完成
    * @summary : 返回工具中断行为。
    *@description : 用于区分可取消工具和必须阻塞等待完成的工具。
    *
    *
                              */
    InterruptBehavior interruptBehavior();

    /*
    * @status : 未完成
    * @summary : 判断工具调用是否只读。
    *@description : 只读结果可用于并发计划和权限默认策略。
    *
    *
                              */
    boolean isReadOnly(I input);

    /*
    * @status : 未完成
    * @summary : 判断工具调用是否可安全并发。
    *@description : 可并发只表示运行时调度许可，不表示绕过权限或审计。
    *
    *
                              */
    boolean isConcurrencySafe(I input);

    /*
    * @status : 未完成
    * @summary : 判断工具调用是否具有破坏性。
    *@description : 用于权限提示、风险展示和执行排序。
    *
    *
                              */
    boolean isDestructive(I input);

    /*
    * @status : 未完成
    * @summary : 返回工具结果最大上下文预算。
    *@description : 超出预算的结果应持久化并用预览替换。
    *
    *
                              */
    int maxResultSize();

    /*
    * @status : 未完成
    * @summary : 渲染给用户查看的工具调用摘要。
    *@description : 该文本用于 TUI 或 headless 展示，不等同于模型上下文序列化。
    *
    *
                              */
    String renderForUser(I input);

    /*
    * @status : 未完成
    * @summary : 将工具输出序列化为模型上下文消息。
    *@description : 输出应遵守预算、脱敏和 tool result 语义。
    *
    *
                              */
    AgentMessage serializeForContext(O output);
}

