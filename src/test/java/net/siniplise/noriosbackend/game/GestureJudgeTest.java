package net.siniplise.noriosbackend.game;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 四枚超限手势的判据 —— 用例逐条对齐 {@code NoriOSNew/_t_gesture.js}。
 *
 * <p>这些判据全是"判松了不会崩、只会让剧情失去重量"的规则：
 * 一枚原本该在特定时刻才响的 fact，一旦变成"随便玩就送"，那段剧情就废了。
 * 所以每条边界都要钉死，尤其是**看着可以放宽、实际放宽就错**的那几条。
 */
class GestureJudgeTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static JsonNode json(String s) {
        return MAPPER.readTree(s);
    }

    /** 真实 capture 结构（proxy.log 实测）。 */
    private static JsonNode capture(String by, String eater, String eaten) {
        return json("""
                {"type":"capture","by":"%s","from":{"piece":"%s","square":"h8"},
                 "captured":{"piece":"%s","square":"h1"},"version":9,"index":0}
                """.formatted(by, eater, eaten));
    }

    // ── chess：必须「她的车吃我的后」 ─────────────────────────────────────

    @Test
    @DisplayName("chess：她的车吃掉我的后 ⇒ 触发")
    void chessHerRookTakesMyQueen() {
        assertThat(GestureJudge.chessCapture("white", capture("black", "r", "q")))
                .isPresent()
                .hasValueSatisfying(w -> assertThat(w).contains("她的车吃掉你的后").contains("Rxh1"));
    }

    @Test
    @DisplayName("chess：我吃掉她的后 ⇒ 不触发（方向反了）")
    void chessPlayerTakesHerQueen() {
        assertThat(GestureJudge.chessCapture("white", capture("white", "r", "q"))).isEmpty();
    }

    @Test
    @DisplayName("chess：她的车吃我的马（不是后）⇒ 不触发")
    void chessHerRookTakesMyKnight() {
        assertThat(GestureJudge.chessCapture("white", capture("black", "r", "n"))).isEmpty();
    }

    @Test
    @DisplayName("chess：她的马吃我的后（不是车）⇒ 不触发")
    void chessHerKnightTakesMyQueen() {
        assertThat(GestureJudge.chessCapture("white", capture("black", "n", "q"))).isEmpty();
    }

    @Test
    @DisplayName("chess：玩家执黑时，她的白车吃我的后 ⇒ 触发（判据跟着 playerSide 走）")
    void chessPlayerIsBlack() {
        assertThat(GestureJudge.chessCapture("black", capture("white", "r", "q"))).isPresent();
    }

    @Test
    @DisplayName("chess：playerSide 缺失时按 white 处理（与 JS 默认值一致）")
    void chessPlayerSideDefaultsToWhite() {
        assertThat(GestureJudge.chessCapture(null, capture("black", "r", "q"))).isPresent();
    }

    @Test
    @DisplayName("chess：事件缺 from/captured 结构 ⇒ 不触发，也不抛异常")
    void chessMalformedEvent() {
        JsonNode bad = json("""
                {"type":"capture","by":"black","captured":"q"}
                """);
        assertThat(GestureJudge.chessCapture("white", bad)).isEmpty();
    }

    // ── pictionary：必须精确等于「水母」 ────────────────────────────────

    @Test
    @DisplayName("pictionary：她猜出「水母」⇒ 触发")
    void pictionaryJellyfish() {
        assertThat(GestureJudge.pictionaryGuess("水母")).isPresent();
    }

    @Test
    @DisplayName("pictionary：她猜「水母灯」⇒ 不触发（严格精确）")
    void pictionaryJellyfishLamp() {
        assertThat(GestureJudge.pictionaryGuess("水母灯")).isEmpty();
    }

    @Test
    @DisplayName("pictionary：她猜「水母伞」⇒ 不触发（严格精确）")
    void pictionaryJellyfishUmbrella() {
        assertThat(GestureJudge.pictionaryGuess("水母伞")).isEmpty();
    }

    @Test
    @DisplayName("pictionary：她猜「章鱼」⇒ 不触发（旧判据的错误行为）")
    void pictionaryOctopus() {
        assertThat(GestureJudge.pictionaryGuess("章鱼")).isEmpty();
    }

    @Test
    @DisplayName("pictionary：带引号/空白的「水母」仍算（先剥引号再比）")
    void pictionaryJellyfishWithQuotes() {
        assertThat(GestureJudge.pictionaryGuess(" 「水母」 ")).isPresent();
        assertThat(GestureJudge.pictionaryGuess("\"水母\"")).isPresent();
    }

    // ── codenames：连续 3 次踩浆果 ─────────────────────────────────────

    @Test
    @DisplayName("codenames：她连续踩 3 次浆果 ⇒ 第 3 次触发")
    void codenamesBerryStreak() {
        var s1 = GestureJudge.codenamesGuess("agent", "bystander", 0);
        assertThat(s1.streak()).isEqualTo(1);
        assertThat(s1.fact()).isEmpty();

        var s2 = GestureJudge.codenamesGuess("agent", "bystander", s1.streak());
        assertThat(s2.streak()).isEqualTo(2);
        assertThat(s2.fact()).isEmpty();

        var s3 = GestureJudge.codenamesGuess("agent", "bystander", s2.streak());
        assertThat(s3.streak()).isEqualTo(3);
        assertThat(s3.fact()).isPresent();
    }

    @Test
    @DisplayName("codenames：玩家踩浆果 ⇒ 一次都不算")
    void codenamesPlayerBerryDoesNotCount() {
        var s = GestureJudge.codenamesGuess("player", "bystander", 0);
        assertThat(s.streak()).isZero();
        assertThat(s.fact()).isEmpty();
    }

    @Test
    @DisplayName("codenames：她中间猜中一次 ⇒ 连击清零，「连续」被打破")
    void codenamesHitResetsStreak() {
        var s = GestureJudge.codenamesGuess("agent", "agent", 2);
        assertThat(s.streak()).isZero();
        assertThat(s.fact()).isEmpty();
    }

    @Test
    @DisplayName("codenames：踩到怪兽（assassin）也打断连击，但不算浆果")
    void codenamesAssassinBreaksStreak() {
        var s = GestureJudge.codenamesGuess("agent", "assassin", 2);
        assertThat(s.streak()).isZero();
        assertThat(s.fact()).isEmpty();
    }

    // ── cakeduel：狼爵士被弃 ───────────────────────────────────────────

    @Test
    @DisplayName("cakeduel：wolfy 按牌表下标被弃 ⇒ 触发")
    void cakeduelWolfyByIndex() {
        JsonNode ev = json("""
                {"type":"card_discarded","cardIds":[2],"zone":"block_pile","version":5,"index":1}
                """);
        assertThat(GestureJudge.cakeduelDiscard(ev, List.of("soldier", "soldier", "wolfy"))).isPresent();
    }

    @Test
    @DisplayName("cakeduel：wolfy 直接以牌名出现 ⇒ 也触发")
    void cakeduelWolfyByName() {
        JsonNode ev = json("""
                {"type":"card_discarded","cardIds":["wolfy"],"zone":"block_pile"}
                """);
        assertThat(GestureJudge.cakeduelDiscard(ev, List.of())).isPresent();
    }

    @Test
    @DisplayName("cakeduel：弃的是士兵 ⇒ 不触发")
    void cakeduelSoldier() {
        JsonNode ev = json("""
                {"type":"card_discarded","cardIds":[0],"zone":"block_pile"}
                """);
        assertThat(GestureJudge.cakeduelDiscard(ev, List.of("soldier", "soldier", "wolfy"))).isEmpty();
    }

    @Test
    @DisplayName("cakeduel：下标越界 / 牌表为空 ⇒ 不触发，也不抛异常")
    void cakeduelOutOfRange() {
        JsonNode ev = json("""
                {"type":"card_discarded","cardIds":[9],"zone":"block_pile"}
                """);
        assertThat(GestureJudge.cakeduelDiscard(ev, List.of("soldier"))).isEmpty();
        assertThat(GestureJudge.cakeduelDiscard(ev, null)).isEmpty();
    }

    // ── 事件归一化：两套封装并存 ───────────────────────────────────────

    @Test
    @DisplayName("事件归一：cakeduel 的 engine 包装要拆，chess 的平铺要原样保留")
    void flattenBothShapes() {
        JsonNode nested = json("""
                {"type":"engine","event":{"type":"game_started","index":0}}
                """);
        assertThat(GameEvents.type(nested)).isEqualTo("game_started");
        assertThat(GameEvents.isStart(GameEvents.type(nested))).isTrue();

        JsonNode flat = json("""
                {"type":"game_start","playerSide":"white","version":1,"index":0}
                """);
        assertThat(GameEvents.type(flat)).isEqualTo("game_start");
        assertThat(GameEvents.isStart(GameEvents.type(flat))).isTrue();
    }

    @Test
    @DisplayName("事件归一：engine 里没有 event 时不能递归拆坏，原样返回")
    void flattenBrokenEngine() {
        JsonNode broken = json("""
                {"type":"engine"}
                """);
        assertThat(GameEvents.type(broken)).isEqualTo("engine");
    }

    @Test
    @DisplayName("整局结束判定：bout_ended 不算整局结束（蛋糕对决要赢 3 轮）")
    void boutEndedIsNotGameOver() {
        assertThat(GameEvents.isOver("game_over")).isTrue();
        assertThat(GameEvents.isOver("game_ended")).isTrue();
        assertThat(GameEvents.isOver("game_outcome")).isTrue();
        assertThat(GameEvents.isOver("bout_ended")).as("一轮结束 ≠ 整局结束").isFalse();
    }
}
