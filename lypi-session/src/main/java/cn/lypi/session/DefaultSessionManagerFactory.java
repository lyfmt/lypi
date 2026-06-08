package cn.lypi.session;

import cn.lypi.contracts.runtime.SessionManagerFactoryPort;
import cn.lypi.contracts.runtime.SessionManagerPort;
import java.nio.file.Path;

public final class DefaultSessionManagerFactory implements SessionManagerFactoryPort {
    /**
     * 打开指定 cwd 下的 session manager。
     */
    @Override
    public SessionManagerPort open(Path cwd, String sessionId) {
        SessionManagerImpl manager = new SessionManagerImpl(cwd);
        manager.openOrCreate(sessionId);
        return manager;
    }
}
