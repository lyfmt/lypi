package cn.lypi.contracts.session;

import java.time.Instant;
import java.util.Map;

/**
 * 保存扩展或本地状态。
 *
 * NOTE: CustomEntry 不进入 LLM context；需要注入上下文时使用 CustomMessageEntry。
 */
public record CustomEntry(
    String id,
    String parentId,
    String customType,
    Map<String, Object> data,
    Instant timestamp
) implements SessionEntry {}
