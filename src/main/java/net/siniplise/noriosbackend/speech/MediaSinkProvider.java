package net.siniplise.noriosbackend.speech;

/**
 * 取当前音频出口。
 *
 * <p>只暴露"拿到现在的 sink"这一件事，避免 {@code speech} 层认识网关实现。
 * 没有连接时返回 {@code null}——调用方必须处理"没人听"这种情况（不能当成错误）。
 */
public interface MediaSinkProvider {

    MediaSink current();
}
