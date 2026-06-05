package cn.lypi.contracts.session;

public interface SessionReplayPort {
    /**
     * 返回当前 leaf 的可恢复 session 视图。
     */
    SessionView currentView();

    /**
     * 返回指定 leaf 的可恢复 session 视图。
     */
    SessionView view(String leafId);
}
