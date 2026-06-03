package cn.lypi.tool;

import cn.lypi.contracts.common.AbortSignal;
import cn.lypi.contracts.tool.ToolUseContext;

/**
 * 统一从工具上下文中提取中断信号。
 */
public final class ToolAbortSupport {
    public static final String METADATA_ABORT_SIGNAL = "abortSignal";
    private static final AbortSignal NOT_ABORTED = () -> false;

    private ToolAbortSupport() {
    }

    /**
     * 返回工具上下文中的中断信号。
     */
    public static AbortSignal signal(ToolUseContext context) {
        if (context == null || context.metadata() == null) {
            return NOT_ABORTED;
        }
        Object value = context.metadata().get(METADATA_ABORT_SIGNAL);
        return value instanceof AbortSignal abortSignal ? abortSignal : NOT_ABORTED;
    }

    /**
     * 判断工具上下文是否已中断。
     */
    public static boolean aborted(ToolUseContext context) {
        return signal(context).aborted();
    }
}
