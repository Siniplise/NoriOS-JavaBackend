package net.siniplise.noriosbackend.game.pictionary;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import net.siniplise.noriosbackend.game.GameTurnReply;
import net.siniplise.noriosbackend.game.pictionary.PictionaryCanvas.Pt;
import net.siniplise.noriosbackend.game.pictionary.PictionaryCanvas.Stroke;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * 你画我猜：渲染 + 请求 + 答案解析。
 *
 * <p>渲染这块**必须用真图验证**（读回来查像素），不能只断言"返回了非空字符串"——
 * 一张全纸色的图也是合法的 PNG，但模型看它跟没画一样。
 */
class PictionaryTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static Stroke line(String color, double width, double... xy) {
        java.util.List<Pt> pts = new java.util.ArrayList<>();
        for (int i = 0; i + 1 < xy.length; i += 2) {
            pts.add(new Pt(xy[i], xy[i + 1]));
        }
        return new Stroke(pts, color, width);
    }

    // ── PNG 渲染 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("★ 渲染出的 PNG 能被解回 256×256，而且真的画上了墨")
    void rendersRealPngWithInk() throws Exception {
        // 一条横线，横穿画面中部
        var strokes = List.of(line("#363636", 4, 0.1, 0.5, 0.9, 0.5));
        String uri = PictionaryCanvas.renderPngDataUri(strokes);

        assertThat(uri).startsWith("data:image/png;base64,");
        byte[] png = Base64.getDecoder().decode(uri.substring("data:image/png;base64,".length()));
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));

        assertThat(img).isNotNull();
        assertThat(img.getWidth()).isEqualTo(PictionaryCanvas.SIZE);
        assertThat(img.getHeight()).isEqualTo(PictionaryCanvas.SIZE);

        // 线的中点应该被画成深色，角上应该还是纸色
        int mid = img.getRGB(PictionaryCanvas.SIZE / 2, PictionaryCanvas.SIZE / 2);
        int corner = img.getRGB(2, 2);
        assertThat(dark(mid)).as("连线中点应有墨").isTrue();
        // getRGB 返回的是带 alpha 的 ARGB（0xFFEDE3CB），要掩掉高 8 位再比颜色
        assertThat(corner & 0xFFFFFF).as("角上是纸色 #EDE3CB").isEqualTo(0xEDE3CB);
    }

    @Test
    @DisplayName("底色是纸色而不是白色（橡皮画的就是纸色，白底会留痕）")
    void paperBackgroundNotWhite() throws Exception {
        var strokes = List.of(line("#363636", 4, 0.5, 0.5, 0.5, 0.5));
        String uri = PictionaryCanvas.renderPngDataUri(strokes);
        byte[] png = Base64.getDecoder().decode(uri.substring(22));
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));

        int bg = img.getRGB(200, 200) & 0xFFFFFF;
        assertThat(bg).isEqualTo(0xEDE3CB);
        assertThat(bg).as("绝不能是纯白").isNotEqualTo(0xFFFFFF);
    }

    @Test
    @DisplayName("没有笔画 ⇒ null（不返回一张空图去误导模型）")
    void noStrokesNoPng() {
        assertThat(PictionaryCanvas.renderPngDataUri(List.of())).isNull();
        assertThat(PictionaryCanvas.renderPngDataUri(null)).isNull();
    }

    @Test
    @DisplayName("笔画点全在画布外 ⇒ 也没有墨，返回 null")
    void outOfBoundsInkIsNull() {
        var strokes = List.of(new Stroke(List.of(new Pt(-0.5, -0.5)), "#000000", 4));
        assertThat(PictionaryCanvas.renderPngDataUri(strokes)).isNull();
    }

    @Test
    @DisplayName("颜色解析：#rrggbb / #rgb / rgb() 都认，认不出用兜底笔色")
    void colorParsing() {
        assertThat(PictionaryCanvas.parseColor("#ff0000")).containsExactly(255, 0, 0);
        assertThat(PictionaryCanvas.parseColor("#f00")).containsExactly(255, 0, 0);
        assertThat(PictionaryCanvas.parseColor("rgb(10, 20, 30)")).containsExactly(10, 20, 30);
        assertThat(PictionaryCanvas.parseColor("rgba(10,20,30,0.5)")).containsExactly(10, 20, 30);
        assertThat(PictionaryCanvas.parseColor(null)).containsExactly(0x36, 0x36, 0x36);
        assertThat(PictionaryCanvas.parseColor("乱七八糟")).containsExactly(0x36, 0x36, 0x36);
    }

    @Test
    @DisplayName("笔宽换算：粗笔半径更大，且被夹在 [1,9]")
    void brushRadius() {
        // width=0 与“缺失”同义，都会走默认 4 ⇒ 半径 2（不是 1）
        assertThat(PictionaryCanvas.brushRadius(0)).isEqualTo(2);
        assertThat(PictionaryCanvas.brushRadius(4)).isGreaterThanOrEqualTo(1);
        assertThat(PictionaryCanvas.brushRadius(400)).isEqualTo(9);
        assertThat(PictionaryCanvas.brushRadius(99999)).as("上限 9").isEqualTo(9);
    }

    @Test
    @DisplayName("batch 解析：丢弃没有 points 的坏笔画")
    void parseBatchTolerant() {
        var batch = MAPPER.readTree("""
                [{"points":[{"x":0.1,"y":0.2}],"color":"#f00","width":8},
                 {"color":"#0f0","width":4},
                 {"points":[]},
                 {"points":[{"x":0.5,"y":0.5}]}]
                """);
        var parsed = PictionaryCanvas.parseBatch(batch);
        assertThat(parsed).hasSize(2);
        assertThat(parsed.get(0).color()).isEqualTo("#f00");
        assertThat(parsed.get(0).points()).hasSize(1);
        assertThat(parsed.get(1).points()).hasSize(1);
        assertThat(PictionaryCanvas.parseBatch(null)).isEmpty();
    }

    // ── 字符画兜底 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("字符画：56×34 行，画线的地方不是空格")
    void asciiShape() {
        var strokes = List.of(line("#000", 4, 0.1, 0.5, 0.9, 0.5));
        String art = PictionaryAscii.render(strokes);
        String[] rows = art.split("\n");

        assertThat(rows).hasSize(PictionaryAscii.H);
        assertThat(rows[0].length()).isEqualTo(PictionaryAscii.W);
        // 中线那一行应该有非空白字符
        assertThat(rows[PictionaryAscii.H / 2].trim()).isNotEmpty();
        // 顶部那一行应该基本是空的
        assertThat(rows[0].trim()).isEmpty();
    }

    @Test
    @DisplayName("字符画：没笔画 ⇒ 空串")
    void asciiEmpty() {
        assertThat(PictionaryAscii.render(List.of())).isEmpty();
        assertThat(PictionaryAscii.render(null)).isEmpty();
    }

    // ── 请求构造 ───────────────────────────────────────────────────────

    private static final String PIC_STATE = """
            {"roles":{"drawer":"player","guesser":"agent"},
             "guesses":["月亮","火车"],
             "pinyin":[["shui"],["mu"]]}
            """;

    @Test
    @DisplayName("她猜：observation 带字数、已猜错清单、不要谐音；clueMode=true")
    void requestShape() {
        PictionaryBoard board = new PictionaryBoard();
        board.addBatch(MAPPER.readTree("""
                [{"points":[{"x":0.2,"y":0.2},{"x":0.8,"y":0.8}],"color":"#363636","width":6}]
                """));
        var pos = PictionaryPosition.from(MAPPER.readTree(PIC_STATE), board);

        assertThat(pos.sheGuesses()).isTrue();
        assertThat(pos.hasPicture()).isTrue();

        var req = pos.toRequest("");
        assertThat(req).isNotNull();
        assertThat(req.game()).isEqualTo("pictionary");
        assertThat(req.clueMode()).as("pictionary 的 clueMode 表示「答案写 clue.word」").isTrue();
        assertThat(req.maxActions()).isEqualTo(1);
        assertThat(req.image()).startsWith("data:image/png;base64,");
        assertThat(req.legalIds()).containsExactly("guess");

        assertThat(req.observation()).contains("答案字数：2 个字");
        assertThat(req.observation()).contains("月亮、火车");
        assertThat(req.observation()).contains("不要按读音联想");
        assertThat(req.observation()).contains("图片附在本条消息里");
    }

    @Test
    @DisplayName("不是她猜（她画、玩家猜）⇒ sheGuesses=false")
    void notHerGuess() {
        var pos = PictionaryPosition.from(MAPPER.readTree("""
                {"roles":{"drawer":"agent","guesser":"player"}}
                """), new PictionaryBoard());
        assertThat(pos.sheGuesses()).isFalse();
    }

    @Test
    @DisplayName("★ 一笔没画 ⇒ 不给请求（空白画面猜出来必然是瞎猜）")
    void noInkNoRequest() {
        var pos = PictionaryPosition.from(MAPPER.readTree(PIC_STATE), new PictionaryBoard());
        assertThat(pos.hasPicture()).isFalse();
        assertThat(pos.toRequest("")).isNull();
        assertThat(pos.observation()).contains("一笔都没画");
    }

    @Test
    @DisplayName("没有 roles 字段 ⇒ 保守判定「不该她猜」")
    void missingRolesIsConservative() {
        var pos = PictionaryPosition.from(MAPPER.readTree("{}"), new PictionaryBoard());
        assertThat(pos.sheGuesses()).as("宁可不动，也不抢玩家的回合").isFalse();
    }

    // ── 答案解析 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("★ 她猜的词从 clue.word 取，剥掉引号")
    void guessFromClue() {
        var reply = GameTurnReply.parse(MAPPER.readTree("""
                {"ok":true,"actions":["guess"],"clue":{"word":"「水母」"},"lines":["是水母吧？"]}
                """), 10);
        assertThat(PictionaryPosition.guessOf(reply)).isEqualTo("水母");
    }

    @Test
    @DisplayName("clue 缺失时退回 actions[0]（兼容老回复）")
    void guessFromActions() {
        var reply = GameTurnReply.parse(MAPPER.readTree("""
                {"ok":true,"actions":["章鱼"],"clue":null}
                """), 10);
        assertThat(PictionaryPosition.guessOf(reply)).isEqualTo("章鱼");
    }

    @Test
    @DisplayName("占位动作 guess 本身不能被当成答案")
    void placeholderIsNotAnswer() {
        var reply = GameTurnReply.parse(MAPPER.readTree("""
                {"ok":true,"actions":["guess"],"clue":null}
                """), 10);
        assertThat(PictionaryPosition.guessOf(reply)).isNull();
    }

    // ── 画布累积器 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("画布累积：多批合并；超过上限丢最旧的")
    void boardAccumulates() {
        PictionaryBoard board = new PictionaryBoard();
        board.addBatch(MAPPER.readTree("""
                [{"points":[{"x":0.1,"y":0.1}],"color":"#000","width":4}]
                """));
        board.addBatch(MAPPER.readTree("""
                [{"points":[{"x":0.2,"y":0.2}],"color":"#000","width":4},
                 {"points":[{"x":0.3,"y":0.3}],"color":"#000","width":4}]
                """));
        assertThat(board.strokeCount()).isEqualTo(3);
        assertThat(board.hasInk()).isTrue();

        board.reset();
        assertThat(board.strokeCount()).isZero();
        assertThat(board.hasInk()).isFalse();
    }

    @Test
    @DisplayName("画布上限 600 笔，超出丢最旧的")
    void boardCapsStrokes() {
        PictionaryBoard board = new PictionaryBoard();
        StringBuilder one = new StringBuilder();
        for (int i = 0; i < PictionaryBoard.MAX_STROKES + 5; i++) {
            one.append(i == 0 ? "[" : ",")
                    .append("{\"points\":[{\"x\":0.5,\"y\":0.5}],\"color\":\"#000\",\"width\":4}");
            if (i == 0) {
                one.append("\n");
            }
        }
        one.append("]");
        board.addBatch(MAPPER.readTree(one.toString()));
        assertThat(board.strokeCount()).isEqualTo(PictionaryBoard.MAX_STROKES);
    }

    @Test
    @DisplayName("board 里的笔画真的能渲染出图（端到端串一次）")
    void boardRenders() {
        PictionaryBoard board = new PictionaryBoard();
        board.addBatch(MAPPER.readTree("""
                [{"points":[{"x":0.2,"y":0.3},{"x":0.6,"y":0.7}],"color":"#b03","width":10}]
                """));
        var pos = PictionaryPosition.from(MAPPER.readTree(PIC_STATE), board);
        assertThat(pos.png()).startsWith("data:image/png;base64,");
        assertThat(pos.toRequest("").legal()).isEqualTo(List.of(Map.of("id", "guess", "desc", "你猜的词（写进 clue.word）")));
    }

    private static boolean dark(int rgb) {
        int r = (rgb >> 16) & 255;
        int g = (rgb >> 8) & 255;
        int b = rgb & 255;
        return r + g + b < 300;
    }
}
