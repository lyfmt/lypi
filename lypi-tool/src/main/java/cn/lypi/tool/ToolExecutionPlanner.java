package cn.lypi.tool;

import cn.lypi.contracts.tool.Tool;
import cn.lypi.contracts.tool.ToolUseRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 规划工具调用的并发和串行批次。
 *
 * NOTE: 计划器只决定执行顺序，不做权限、校验或实际执行。
 */
public final class ToolExecutionPlanner {
    public record ResolvedToolCall(ToolUseRequest request, Tool<Map<String, Object>, ?> tool) {}

    public record Batch(boolean parallel, List<ResolvedToolCall> calls) {}

    /**
     * 将已解析工具调用分组为保持原始顺序的执行批次。
     *
     * 只读且并发安全的连续调用会进入同一并行批次，其他调用单独串行执行。
     */
    public List<Batch> plan(List<ResolvedToolCall> calls) {
        if (calls == null || calls.isEmpty()) {
            return List.of();
        }
        List<Batch> batches = new ArrayList<>();
        List<ResolvedToolCall> currentParallel = new ArrayList<>();
        for (ResolvedToolCall call : calls) {
            if (canRunInParallel(call)) {
                currentParallel.add(call);
                continue;
            }
            flushParallelBatch(batches, currentParallel);
            batches.add(new Batch(false, List.of(call)));
        }
        flushParallelBatch(batches, currentParallel);
        return List.copyOf(batches);
    }

    private void flushParallelBatch(List<Batch> batches, List<ResolvedToolCall> currentParallel) {
        if (currentParallel.isEmpty()) {
            return;
        }
        batches.add(new Batch(true, List.copyOf(currentParallel)));
        currentParallel.clear();
    }

    private boolean canRunInParallel(ResolvedToolCall call) {
        Map<String, Object> input = call.request().input() == null ? Map.of() : call.request().input();
        try {
            return call.tool().isReadOnly(input) && call.tool().isConcurrencySafe(input);
        } catch (RuntimeException exception) {
            return false;
        }
    }
}
