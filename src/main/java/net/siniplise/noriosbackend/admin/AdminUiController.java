package net.siniplise.noriosbackend.admin;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 管理网页本体：{@code GET /admin} → {@code static/admin.html}。
 *
 * <p><b>为什么不用 Spring 的静态资源映射直接给</b>：静态资源是按文件名找的
 * （{@code /admin.html} 可以，{@code /admin} 不行）。这里显式映射一次，
 * 是为了让地址是干净的 {@code /admin}——贴着根路径手打不容易错。
 *
 * <p><b>为什么必须显式 {@code produces}</b>：路径没有扩展名，WebFlux 猜不出类型，
 * 不声明的话浏览器会当成下载。
 *
 * <p>注意：{@code HttpReverseProxy} 里必须放行 {@code /admin}，否则这个请求会被
 * 转发给上游 exe 而 404（那是反代的职责，见那个类的放行列表）。
 */
@RestController
public class AdminUiController {

    @GetMapping(value = "/admin", produces = MediaType.TEXT_HTML_VALUE)
    public Mono<Resource> adminPage() {
        return Mono.just(new ClassPathResource("static/admin.html"));
    }
}
