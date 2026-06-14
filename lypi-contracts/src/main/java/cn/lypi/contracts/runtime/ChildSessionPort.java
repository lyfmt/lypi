package cn.lypi.contracts.runtime;

import cn.lypi.contracts.session.ChildSessionRequest;
import cn.lypi.contracts.session.SessionHandle;

public interface ChildSessionPort {
    /**
     * 创建独立 child session。
     *
     * NOTE: 实现必须记录 parent session 和 spawn entry 关系，不能复制父分支。
     */
    SessionHandle create(ChildSessionRequest request);
}
