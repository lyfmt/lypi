package cn.lypi.contracts.session;

/**
 * 表示当前 session 与 leaf 指针。
 *
 * NOTE: 该视图不得包含 recent tools、file changes、permission decisions 或 memory writes。
 */
public record SessionView(String sessionId, String leafId) {}
