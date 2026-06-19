package cn.lypi.tool;

import cn.lypi.contracts.security.NetworkPolicyAmendment;
import cn.lypi.contracts.security.PermissionGrantScope;
import cn.lypi.contracts.security.PermissionUpdate;
import java.util.List;

/**
 * 持久化 Codex 风格权限修订。
 */
public interface PermissionAmendmentStore {
    /**
     * 追加一条 exec policy 权限修订。
     */
    void appendPermissionUpdate(PermissionUpdate update, PermissionGrantScope scope);

    /**
     * 追加一条带会话归属的 exec policy 权限修订。
     */
    default void appendPermissionUpdate(PermissionUpdate update, PermissionGrantScope scope, String sessionId) {
        appendPermissionUpdate(update, scope);
    }

    /**
     * 追加一条带会话和 turn 归属的 exec policy 权限修订。
     */
    default void appendPermissionUpdate(PermissionUpdate update, PermissionGrantScope scope, String sessionId, String turnId) {
        appendPermissionUpdate(update, scope, sessionId);
    }

    /**
     * 追加一条网络策略权限修订。
     */
    void appendNetworkPolicyAmendment(NetworkPolicyAmendment amendment, PermissionGrantScope scope);

    /**
     * 追加一条带会话归属的网络策略权限修订。
     */
    default void appendNetworkPolicyAmendment(
        NetworkPolicyAmendment amendment,
        PermissionGrantScope scope,
        String sessionId
    ) {
        appendNetworkPolicyAmendment(amendment, scope);
    }

    /**
     * 追加一条带会话和 turn 归属的网络策略权限修订。
     */
    default void appendNetworkPolicyAmendment(
        NetworkPolicyAmendment amendment,
        PermissionGrantScope scope,
        String sessionId,
        String turnId
    ) {
        appendNetworkPolicyAmendment(amendment, scope, sessionId);
    }

    /**
     * 读取指定作用域下的 exec policy 权限修订。
     */
    List<PermissionUpdate> readPermissionUpdates(PermissionGrantScope scope);

    /**
     * 读取指定作用域和会话下的 exec policy 权限修订。
     */
    default List<PermissionUpdate> readPermissionUpdates(PermissionGrantScope scope, String sessionId) {
        return readPermissionUpdates(scope);
    }

    /**
     * 读取指定作用域、会话和 turn 下的 exec policy 权限修订。
     */
    default List<PermissionUpdate> readPermissionUpdates(PermissionGrantScope scope, String sessionId, String turnId) {
        return readPermissionUpdates(scope, sessionId);
    }

    /**
     * 读取指定作用域下的网络策略权限修订。
     */
    List<NetworkPolicyAmendment> readNetworkPolicyAmendments(PermissionGrantScope scope);

    /**
     * 读取指定作用域和会话下的网络策略权限修订。
     */
    default List<NetworkPolicyAmendment> readNetworkPolicyAmendments(PermissionGrantScope scope, String sessionId) {
        return readNetworkPolicyAmendments(scope);
    }

    /**
     * 读取指定作用域、会话和 turn 下的网络策略权限修订。
     */
    default List<NetworkPolicyAmendment> readNetworkPolicyAmendments(
        PermissionGrantScope scope,
        String sessionId,
        String turnId
    ) {
        return readNetworkPolicyAmendments(scope, sessionId);
    }
}
