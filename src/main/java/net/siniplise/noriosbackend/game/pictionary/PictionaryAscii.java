package net.siniplise.noriosbackend.game.pictionary;

import java.util.List;
import net.siniplise.noriosbackend.game.pictionary.PictionaryCanvas.Stroke;

/**
 * 字符画兜底：PNG 渲染不出来时，用 ASCII 让模型"看出形状"。
 *
 * <p>移植自 `nori_game.js#strokeAscii`。它有两个刻意之处：
 * <ol>
 *   <li><b>带密度分档</b>而不是只用 {@code #}：单字符时线条太稀疏，模型看不出形状；
 *       这里按"每格落了多少个采样点"分档（{@code ' .:-=+*#%@'}），粗细才有层次。</li>
 *   <li><b>线段要插值补齐</b>：只打采样点会让快速划过的笔变成一串断开的点，
 *       而不是一条线。</li>
 * </ol>
 *
 * <p>分辨率 {@value #W}×{@value #H}——这个比例（约 1.6:1）接近字符的高宽比，
 * 所以画出来的形状不会被压扁。
 */
public final class PictionaryAscii {

    static final int W = 56;
    static final int H = 34;

    /** 密度阶梯：索引越大越黑。注意 {@code n==0} 单独处理成空格，所以 '.' 实际用不到。 */
    private static final String RAMP = " .:-=+*#%@";

    private PictionaryAscii() {
    }

    /** @return 字符画；一笔都没有时返回空串 */
    public static String render(List<Stroke> strokes) {
        if (strokes == null || strokes.isEmpty()) {
            return "";
        }
        int[] grid = new int[W * H];
        long ink = 0;

        for (Stroke s : strokes) {
            List<int[]> pts = s.points().stream()
                    .map(p -> new int[] {(int) Math.round(p.x() * (W - 1)), (int) Math.round(p.y() * (H - 1))})
                    .toList();
            if (pts.isEmpty()) {
                continue;
            }
            if (pts.size() == 1) {
                if (put(grid, pts.get(0)[0], pts.get(0)[1])) {
                    ink++;
                }
                continue;
            }
            for (int i = 1; i < pts.size(); i++) {
                int x0 = pts.get(i - 1)[0];
                int y0 = pts.get(i - 1)[1];
                int x1 = pts.get(i)[0];
                int y1 = pts.get(i)[1];
                int steps = Math.max(Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0)), 1);
                for (int k = 0; k <= steps; k++) {
                    int x = (int) Math.round(x0 + ((x1 - x0) * (double) k) / steps);
                    int y = (int) Math.round(y0 + ((y1 - y0) * (double) k) / steps);
                    if (put(grid, x, y)) {
                        ink++;
                    }
                }
            }
        }
        if (ink == 0) {
            return "";
        }

        StringBuilder sb = new StringBuilder(H * (W + 1));
        for (int y = 0; y < H; y++) {
            if (y > 0) {
                sb.append('\n');
            }
            for (int x = 0; x < W; x++) {
                int n = grid[y * W + x];
                if (n == 0) {
                    sb.append(' ');
                } else {
                    sb.append(RAMP.charAt(Math.min(RAMP.length() - 1, 1 + Math.min(n, 7))));
                }
            }
        }
        return sb.toString();
    }

    private static boolean put(int[] grid, int x, int y) {
        if (x < 0 || x >= W || y < 0 || y >= H) {
            return false;
        }
        grid[y * W + x]++;
        return true;
    }
}
