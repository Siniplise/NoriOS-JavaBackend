package net.siniplise.noriosbackend.runtime;

/**
 * 线程所有权守卫：可变状态只允许「属主线程」碰。
 *
 * <p>为什么不是加锁：本项目的心智模型是 <b>每会话一个线程</b>（{@link SessionLoop}），
 * 状态变更全部串行。加锁会把这个模型变模糊——一旦有人加了一把锁，下一个人就会以为可以并发进来。
 * 守卫的做法相反：**进错线程直接抛**，把并发 bug 变成第一次运行就炸的确定性错误。
 *
 * <p>代价极低（一次 volatile 读 + 一次比较），所以可以放在热路径上；
 * 但不要放在每帧都走的拷贝循环里——那里靠设计保证，不靠运行时检查。
 */
public final class ThreadGuard {

    private final String owner;
    private volatile long ownerThreadId;
    private volatile String ownerThreadName;

    public ThreadGuard(String owner) {
        this.owner = owner;
        claim();
    }

    /** 把当前线程登记为属主（启动或交接时调用）。 */
    public void claim() {
        Thread current = Thread.currentThread();
        this.ownerThreadId = current.threadId();
        this.ownerThreadName = current.getName();
    }

    /** 当前线程是不是属主。 */
    public boolean ownedByCurrentThread() {
        return Thread.currentThread().threadId() == ownerThreadId;
    }

    /** 不是属主就抛，并带上两边线程名——排查时一眼看出是谁越界。 */
    public void assertOwner() {
        Thread current = Thread.currentThread();
        if (current.threadId() != ownerThreadId) {
            throw new IllegalStateException(owner + " 只能由属主线程 [" + ownerThreadName
                    + "] 访问，当前线程是 [" + current.getName() + "]");
        }
    }

    public String ownerName() {
        return ownerThreadName;
    }
}
