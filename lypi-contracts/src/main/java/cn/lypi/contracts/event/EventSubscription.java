package cn.lypi.contracts.event;

public interface EventSubscription extends AutoCloseable {
    /*
    * @status : 未完成
    * @summary : 取消事件订阅。
    *@description : 关闭后订阅者不应再收到新事件。
    *
    *
                              */
    @Override
    void close();
}

