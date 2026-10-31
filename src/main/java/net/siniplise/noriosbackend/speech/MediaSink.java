package net.siniplise.noriosbackend.speech;

import net.siniplise.noriosbackend.contract.ArcadeFrame;

/**
 * 媒体通道的发送端：把一帧音频推给浏览器。
 *
 * <p>抽成接口是为了让 {@code SessionRuntime} 不依赖 WebFlux——单元测试可以塞一个"收集器"
 * 进去，断言帧的顺序与序号，而不必真的开一条 WS。
 *
 * <p><b>线程约束</b>：{@link #send} 只允许从会话线程调用。
 * 帧的 `seq` 单调性靠"分配序号"和"发送"在同一线程完成来保证（Q5）；
 * 一旦允许并发调用，两个线程可能各自 `seq++` 然后乱序发出，客户端按 seq 组装就会拼错音频。
 */
public interface MediaSink {

    /** 推一帧；实现必须立即写出（不缓存整句）。 */
    void send(ArcadeFrame frame);

    /** 连接是否还活着；false 时调用方应跳过发送。 */
    boolean isOpen();
}
