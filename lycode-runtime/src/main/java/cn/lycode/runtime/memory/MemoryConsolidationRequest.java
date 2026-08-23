package cn.lycode.runtime.memory;

/**
 * 后台记忆沉淀执行请求。
 */
public record MemoryConsolidationRequest(String sessionId, String forkPointEntryId) {}
