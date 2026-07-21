package cn.lypi.tool;

import cn.lypi.contracts.common.AbortSignal;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.model.AssistantDone;
import cn.lypi.contracts.model.AssistantError;
import cn.lypi.contracts.model.AssistantEventStream;
import cn.lypi.contracts.model.AssistantStart;
import cn.lypi.contracts.model.AssistantStreamEvent;
import cn.lypi.contracts.model.AssistantStreamResult;
import cn.lypi.contracts.model.ProviderFallbackNotice;
import cn.lypi.contracts.model.ProviderRetryNotice;
import cn.lypi.contracts.model.TextDelta;
import cn.lypi.contracts.model.ThinkingDelta;
import cn.lypi.contracts.model.ToolCallDelta;
import cn.lypi.contracts.runtime.AiProviderRuntimePort;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.tool.Tool;
import cn.lypi.contracts.tool.ToolUseContext;
import cn.lypi.contracts.tool.ToolUseRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.Objects;

/**
 * 使用当前模型对 AUTO 模式下的工具调用进行独立权限复核。
 */
public final class ModelPermissionReviewer implements PermissionReviewer {
    private static final ObjectMapper JSON = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build();
    private final AiProviderRuntimePort provider;
    private final PermissionReviewContextBuilder contextBuilder;

    public ModelPermissionReviewer(AiProviderRuntimePort provider) {
        this.provider = Objects.requireNonNull(provider, "provider must not be null");
        this.contextBuilder = new PermissionReviewContextBuilder();
    }

    @Override
    public PermissionGateResult review(
        ToolUseRequest request,
        Tool<?, ?> tool,
        ToolUseContext context,
        ContextSnapshot contextSnapshot,
        PermissionDecision decision
    ) {
        AbortSignal abortSignal = ToolAbortSupport.signal(context);
        if (abortSignal.aborted()) {
            return denied("AUTO 权限复核已取消。");
        }
        if (contextSnapshot == null) {
            return denied("AUTO 权限复核缺少当前上下文。");
        }

        try {
            ContextSnapshot reviewerContext = contextBuilder.build(request, tool, context, contextSnapshot, decision);
            return reviewStream(reviewerContext, abortSignal);
        } catch (RuntimeException exception) {
            return denied("AUTO 权限复核失败。");
        }
    }

    private PermissionGateResult reviewStream(ContextSnapshot reviewerContext, AbortSignal abortSignal) {
        StringBuilder output = new StringBuilder();
        boolean done = false;
        try (AssistantEventStream stream = provider.stream(
            reviewerContext,
            AiProviderRuntimePort.emptyTools(),
            abortSignal
        )) {
            if (stream == null) {
                return denied("AUTO 权限复核未返回结果。");
            }
            for (AssistantStreamEvent event : stream) {
                if (abortSignal.aborted()) {
                    return denied("AUTO 权限复核已取消。");
                }
                if (done) {
                    return denied("AUTO 权限复核返回了意外输出。");
                }
                if (event instanceof TextDelta delta) {
                    output.append(Objects.toString(delta.text(), ""));
                } else if (event instanceof AssistantDone assistantDone) {
                    if (assistantDone.stopReason().filter("tool_calls"::equalsIgnoreCase).isPresent()) {
                        return denied("AUTO 权限复核返回了意外工具调用。");
                    }
                    done = true;
                } else if (event instanceof AssistantError error) {
                    return denied("AUTO 权限复核 provider 失败: " + safeReason(error.message()));
                } else if (event instanceof ToolCallDelta) {
                    return denied("AUTO 权限复核返回了意外工具调用。");
                } else if (!(event instanceof AssistantStart
                    || event instanceof ThinkingDelta
                    || event instanceof ProviderFallbackNotice
                    || event instanceof ProviderRetryNotice)) {
                    return denied("AUTO 权限复核返回了意外输出。");
                }
            }

            if (abortSignal.aborted()) {
                return denied("AUTO 权限复核已取消。");
            }
            AssistantStreamResult result = stream.result();
            if (result == null || result.aborted()) {
                return denied("AUTO 权限复核已取消。");
            }
            if (result.error().isPresent()) {
                return denied("AUTO 权限复核 provider 失败: " + safeReason(result.error().orElseThrow().message()));
            }
            if (!done || !result.completed()) {
                return denied("AUTO 权限复核输出不完整。");
            }
        }
        return parseDecision(output.toString());
    }

    private PermissionGateResult parseDecision(String output) {
        if (output == null || output.isBlank()) {
            return denied("AUTO 权限复核返回了空结果。");
        }
        try {
            JsonNode root = JSON.readTree(output);
            if (root == null
                || !root.isObject()
                || root.size() != 2
                || !root.path("decision").isTextual()
                || !root.path("reason").isTextual()) {
                return denied("AUTO 权限复核返回了非法 JSON。");
            }
            String decision = root.path("decision").textValue();
            String reason = root.path("reason").textValue().strip();
            if (reason.isBlank() || !("allow".equals(decision) || "deny".equals(decision))) {
                return denied("AUTO 权限复核返回了非法 JSON。");
            }
            return "allow".equals(decision) ? PermissionGateResult.allow() : denied(reason);
        } catch (JsonProcessingException exception) {
            return denied("AUTO 权限复核返回了非法 JSON。");
        }
    }

    private PermissionGateResult denied(String reason) {
        return PermissionGateResult.deny(safeReason(reason));
    }

    private String safeReason(String reason) {
        String safe = Objects.toString(reason, "").strip();
        return safe.isBlank() ? "未提供原因。" : safe;
    }
}
