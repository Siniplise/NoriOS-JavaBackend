package net.siniplise.noriosbackend.store;

/**
 * 台词状态的存取口。
 *
 * <p>{@link #save} 是**异步**的：它会在会话线程上被调用（台词一触发就要落盘），
 * 如果同步写磁盘，一次 fsync 就能把帧序拖歪。真正的写盘在单独的写线程里做。
 *
 * <p>刻意不提供"删除"和"分页"——台词状态只有一份，删除等于清档，
 * 那是 {@code save} 模块的事，不该从这一层漏出去。
 */
public interface ScriptStateStore extends AutoCloseable {

    /** 读上次的状态；没有记录或读坏了都返回 {@link ScriptStateSnapshot#empty()}。 */
    ScriptStateSnapshot load();

    /** 异步落盘（合并写：短时间内多次调用只会写成最后一份）。 */
    void save(ScriptStateSnapshot snapshot);

    /** 等待在途写入完成（停机时用）。 */
    void flush();

    @Override
    void close();
}
