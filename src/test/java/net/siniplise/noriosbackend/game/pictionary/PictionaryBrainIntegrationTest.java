package net.siniplise.noriosbackend.game.pictionary;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import net.siniplise.noriosbackend.game.GameTurnReply;
import net.siniplise.noriosbackend.game.HttpGameBrain;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.json.JsonMapper;

/**
 * 你画我猜真实链路：**我们渲染的图，她到底看不看得懂**。
 *
 * <p>这是整条链路上唯一无法用单测证明的一环：像素对了、PNG 对了、字段对了，
 * 但模型可能根本没把图当图看（比如 base64 被截断、或者它忽略 image 字段）。
 * 唯一的验证方式是画个明确的形状，问她像什么。
 *
 * <p>断言刻意留了余地：模型看图有随机性，不要求一定答"三角形"，
 * 但要求**给出一个短词**——如果图没被看到，她通常会答"看不出"或返回空。
 */
class PictionaryBrainIntegrationTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static boolean personaUp() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", 25503), 600);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** 一个明确的三角形：顶角 + 底边。 */
    private static PictionaryBoard triangle() {
        PictionaryBoard board = new PictionaryBoard();
        String batch = """
                [{"points":[{"x":0.50,"y":0.12},{"x":0.20,"y":0.82}],"color":"#363636","width":10},
                 {"points":[{"x":0.20,"y":0.82},{"x":0.80,"y":0.82}],"color":"#363636","width":10},
                 {"points":[{"x":0.80,"y":0.82},{"x":0.50,"y":0.12}],"color":"#363636","width":10}]
                """;
        board.addBatch(MAPPER.readTree(batch));
        return board;
    }

    @Test
    @DisplayName("★ 真实调用：她能看着我们渲染的三角形给出一个词")
    @Timeout(45)
    void realBrainReadsOurPng() {
        Assumptions.assumeTrue(personaUp(), "25503 未运行，跳过真实大脑测试");

        PictionaryBoard board = triangle();
        var pic = MAPPER.readTree("""
                {"roles":{"drawer":"player","guesser":"agent"},"guesses":[],"pinyin":[["san"],["jiao"],["xing"]]}
                """);
        PictionaryPosition pos = PictionaryPosition.from(pic, board);

        assertThat(pos.hasPicture()).isTrue();
        var req = pos.toRequest("");
        assertThat(req).isNotNull();
        assertThat(req.image()).as("必须真的带图").startsWith("data:image/png;base64,");
        // 把图存一份供人工核对（build 目录，不入库）
        try {
            Path out = Path.of("build", "pic-sample.txt");
            Files.createDirectories(out.getParent());
            Files.writeString(out, req.image(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // 存不了就算了，不影响断言
        }

        GameTurnReply reply = new HttpGameBrain("http://127.0.0.1:25503")
                .turn(req).block(Duration.ofSeconds(35));

        assertThat(reply).as("大脑必须 ok=true").isNotNull();
        String guess = PictionaryPosition.guessOf(reply);
        assertThat(guess).as("看着三角形她应该给得出一个词").isNotNull();
        assertThat(guess.length()).as("答案是个词，不是一段话").isLessThanOrEqualTo(12);
        assertThat(reply.lines()).as("她应当说说自己看到了什么").isNotEmpty();
    }

    @Test
    @DisplayName("★ 真实调用：空白画面不该发起猜测（我们这边就已经拦住了）")
    @Timeout(10)
    void blankCanvasNeverAsks() {
        var pic = MAPPER.readTree("""
                {"roles":{"drawer":"player","guesser":"agent"},"guesses":[],"pinyin":[["san"]]}
                """);
        var pos = PictionaryPosition.from(pic, new PictionaryBoard());
        assertThat(pos.hasPicture()).isFalse();
        assertThat(pos.toRequest("")).isNull();
    }
}
