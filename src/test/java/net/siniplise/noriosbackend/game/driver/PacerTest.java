package net.siniplise.noriosbackend.game.driver;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 节奏闸门。
 *
 * <p>这些数字不是随便定的，都是从 `nori_game.js` 的实测常量抄来的
 * （前端动画时长是读 bundle 得到的，不是估的）。所以这里把"每个卡带至少等多久"钉住——
 * 将来谁想调小，至少会先看到这条测试并想一下"动画播得完吗"。
 */
class PacerTest {

    @Test
    @DisplayName("各卡带的最短思考时间（象棋最长，画猜最短）")
    void thinkMsPerCartridge() {
        assertThat(Pacer.thinkMs("chess")).as("想棋").isEqualTo(2600);
        assertThat(Pacer.thinkMs("codenames")).isEqualTo(1600);
        assertThat(Pacer.thinkMs("cakeduel")).isEqualTo(1400);
        assertThat(Pacer.thinkMs("pictionary"))
                .as("由停笔触发、已有 700ms 去抖，再等太久会显得迟钝")
                .isEqualTo(1200);
        assertThat(Pacer.thinkMs("chat")).as("非游戏卡带不节流").isZero();
        assertThat(Pacer.thinkMs(null)).isZero();
    }

    @Test
    @DisplayName("多步动作间隔 2200ms（JS 从 1100 调到这个值才跟得上动画）")
    void stepMs() {
        assertThat(Pacer.STEP_MS).isEqualTo(2200);
    }

    @Test
    @DisplayName("质疑揭示静默 3600ms（前端实测 ≈1000+3000）")
    void afterRevealMs() {
        assertThat(Pacer.AFTER_REVEAL_MS).isEqualTo(3600);
        assertThat(Pacer.AFTER_REVEAL_MS)
                .as("必须覆盖前端 CHALLENGE_PRE_REVEAL_PAUSE(1000) + REVEAL_HOLD(3000)")
                .isGreaterThanOrEqualTo(4000 - 500);
    }

    @Test
    @DisplayName("waitMs：大脑已经想够时间就不再等")
    void waitAlreadyEnough() {
        // 象棋要 2600；大脑花了 3000 ⇒ 不用再等
        assertThat(Pacer.waitMs(2600, 3000)).isZero();
        assertThat(Pacer.waitMs(2600, 2600)).isZero();
        // 大脑只花了 1200 ⇒ 还要等 1400
        assertThat(Pacer.waitMs(2600, 1200)).isEqualTo(1400);
    }

    @Test
    @DisplayName("waitMs：负数/异常输入不能返回负延迟")
    void waitNegativeSafe() {
        assertThat(Pacer.waitMs(2600, -500)).isEqualTo(2600);
        assertThat(Pacer.waitMs(0, 0)).isZero();
        assertThat(Pacer.waitMs(-100, 0)).isZero();
    }
}
