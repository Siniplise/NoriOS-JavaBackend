package net.siniplise.noriosbackend.game.pictionary;

import java.util.ArrayList;
import java.util.List;
import net.siniplise.noriosbackend.game.pictionary.PictionaryCanvas.Stroke;
import tools.jackson.databind.JsonNode;

/**
 * 画布累积器：把玩家一帧一帧发上来的笔画攒成一张画。
 *
 * <p><b>为什么需要它</b>：上游**不会回传**笔画——`submitStrokeBatch` 是客户端发上去的命令，
 * 服务端的 `gameState` 里没有"这张画长什么样"。所以要喂图给模型，我们只能自己攒。
 *
 * <p><b>为什么保留上限 {@value #MAX_STROKES} 条</b>：一局画下来可能上千笔，
 * 全留着会让每次渲染都变慢，而且早期的笔划往往已经被擦了。丢最旧的（与 JS 一致）
 * 在"画了很久"的场景下更接近当前画面。
 *
 * <p>线程：这个类只被 {@code SessionLoop} 线程碰（上行帧的旁路观察与驱动都在那里），不加锁。
 */
public final class PictionaryBoard {

    /** 最多保留的笔画数（对齐 JS 的 {@code while (strokes.length > 600) shift()}）。 */
    public static final int MAX_STROKES = 600;

    private final List<Stroke> strokes = new ArrayList<>();

    /** 吃掉一批笔画（来自 {@code submitStrokeBatch} 的 {@code batch}）。 */
    public void addBatch(JsonNode batch) {
        List<Stroke> parsed = PictionaryCanvas.parseBatch(batch);
        if (parsed.isEmpty()) {
            return;
        }
        strokes.addAll(parsed);
        // 超限就丢最旧的：新画的才是当前画面
        while (strokes.size() > MAX_STROKES) {
            strokes.remove(0);
        }
    }

    /** 换轮/换局时清空。 */
    public void reset() {
        strokes.clear();
    }

    public List<Stroke> strokes() {
        return List.copyOf(strokes);
    }

    public boolean hasInk() {
        return !strokes.isEmpty();
    }

    public int strokeCount() {
        return strokes.size();
    }
}
