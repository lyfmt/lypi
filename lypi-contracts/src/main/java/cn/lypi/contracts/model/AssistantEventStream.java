package cn.lypi.contracts.model;

public interface AssistantEventStream extends Iterable<AssistantStreamEvent>, AutoCloseable {
    /**
     * 返回模型流最终结果。
     *
     * NOTE: 调用方应在消费结束、异常处理或关闭后读取结果；实现必须返回稳定快照。
     */
    AssistantStreamResult result();

    /**
     * 关闭模型流并释放底层 provider 连接。
     *
     * NOTE: close 必须幂等；调用方应使用 try-with-resources 管理生命周期。
     */
    @Override
    void close();
}
