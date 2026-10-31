package net.siniplise.noriosbackend.game.pictionary;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import tools.jackson.databind.JsonNode;

/**
 * 把玩家的笔画渲染成 PNG，喂给能看图的模型。
 *
 * <p>移植自 `nori_game.js#strokePng`，**逐像素逻辑刻意保持一致**——因为猜词效果取决于
 * 模型看到的图，而"看起来差不多"的渲染可能让线条变细到看不见、或粗到糊成一片。
 *
 * <h2>三个不能想当然的地方</h2>
 * <ol>
 *   <li><b>底色是纸色 {@code #EDE3CB}，不是白色</b>。前端的「橡皮」画的是**纸色**，
 *       所以白底上画米黄会留痕——只有底色等于纸色，"擦除"才真的是擦除。</li>
 *   <li><b>笔刷半径是关键参数</b>：{@code r = clamp(round(width/400 * S/2 + 1), 1, 9)}。
 *       写死成 2 或 3 会让细笔断成虚线、粗笔变成墨块。</li>
 *   <li><b>相邻采样点之间要插值</b>。前端按时间采样，快速划一笔时两点能隔几十像素，
 *       只画采样点会得到一串彼此分离的圆点，而不是一条线。</li>
 * </ol>
 *
 * <p><b>与 JS 唯一的实现差异</b>：它手写了 PNG 编码器（crc32 + chunk），Java 直接用
 * {@link ImageIO}。输出同一种格式，没必要照抄那部分。
 */
public final class PictionaryCanvas {

    /** 画布边长。与 JS 的 {@code PIC_PNG_SIZE} 一致。 */
    public static final int SIZE = 256;

    /** 纸色（前端 {@code os.default.paper}）。 */
    private static final int PAPER_R = 0xED;
    private static final int PAPER_G = 0xE3;
    private static final int PAPER_B = 0xCB;

    /** 兜底笔色 = 前端默认的 graphite。 */
    private static final int[] DEFAULT_INK = {0x36, 0x36, 0x36};

    private static final Pattern HEX6 = Pattern.compile("^#([0-9a-f]{6})$", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEX3 = Pattern.compile("^#([0-9a-f]{3})$", Pattern.CASE_INSENSITIVE);
    private static final Pattern RGB = Pattern.compile("^rgba?\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)",
            Pattern.CASE_INSENSITIVE);

    /** 一个采样点（归一化 0..1）。 */
    public record Pt(double x, double y) {
    }

    /**
     * 一笔。
     *
     * @param color 前端给的颜色串（{@code #rrggbb} / {@code #rgb} / {@code rgb()}）
     * @param width 笔宽（前端用 0~400 量纲，不是像素）
     */
    public record Stroke(List<Pt> points, String color, double width) {
    }

    private PictionaryCanvas() {
    }

    /**
     * 从玩家 {@code submitStrokeBatch} 的 {@code batch} 解析笔画。
     *
     * <p>宽容：没有 {@code points} 或点为空的一律丢弃——一个坏笔画不该让整张图渲染失败。
     */
    public static List<Stroke> parseBatch(JsonNode batch) {
        List<Stroke> out = new ArrayList<>();
        if (batch == null || !batch.isArray()) {
            return out;
        }
        for (JsonNode st : batch) {
            if (st == null || !st.isObject()) {
                continue;
            }
            JsonNode pts = st.get("points");
            if (pts == null || !pts.isArray() || pts.isEmpty()) {
                continue;
            }
            List<Pt> points = new ArrayList<>();
            for (JsonNode p : pts) {
                if (p == null || !p.isObject()) {
                    continue;
                }
                points.add(new Pt(num(p, "x"), num(p, "y")));
            }
            if (points.isEmpty()) {
                continue;
            }
            JsonNode w = st.get("width");
            JsonNode c = st.get("color");
            out.add(new Stroke(points,
                    (c == null || c.isNull()) ? null : c.asString(),
                    (w == null || !w.isNumber()) ? 4 : w.asDouble()));
        }
        return out;
    }

    /**
     * 渲染成 {@code data:image/png;base64,...}。
     *
     * @return 没有留下任何墨迹时返回 {@code null}（调用方据此退回字符画或干脆不猜）
     */
    public static String renderPngDataUri(List<Stroke> strokes) {
        if (strokes == null || strokes.isEmpty()) {
            return null;
        }
        BufferedImage img = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        int paper = (PAPER_R << 16) | (PAPER_G << 8) | PAPER_B;
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                img.setRGB(x, y, paper);
            }
        }

        long ink = 0;
        for (Stroke s : strokes) {
            int[] col = parseColor(s.color());
            int rgb = (col[0] << 16) | (col[1] << 8) | col[2];
            int r = brushRadius(s.width());

            List<int[]> pts = new ArrayList<>(s.points().size());
            for (Pt p : s.points()) {
                pts.add(new int[] {(int) Math.round(p.x() * (SIZE - 1)), (int) Math.round(p.y() * (SIZE - 1))});
            }
            if (pts.isEmpty()) {
                continue;
            }
            if (pts.size() == 1) {
                ink += brush(img, pts.get(0)[0], pts.get(0)[1], r, rgb);
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
                    ink += brush(img, x, y, r, rgb);
                }
            }
        }
        if (ink == 0) {
            return null;
        }

        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
            if (!ImageIO.write(img, "png", out)) {
                return null;
            }
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /**
     * 笔刷：以 (cx,cy) 为心、半径 r 的实心圆。
     *
     * <p>用圆而不是方，是因为方形笔尖在斜线上会出现明显的阶梯感，
     * 而模型对"线条是不是顺的"很敏感。
     *
     * @return 实际写下的像素数（用来判断"有没有留下墨迹"）
     */
    private static int brush(BufferedImage img, int cx, int cy, int r, int rgb) {
        int painted = 0;
        for (int dy = -r; dy <= r; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                if (dx * dx + dy * dy > r * r) {
                    continue;
                }
                int x = cx + dx;
                int y = cy + dy;
                if (x < 0 || y < 0 || x >= SIZE || y >= SIZE) {
                    continue;
                }
                img.setRGB(x, y, rgb);
                painted++;
            }
        }
        return painted;
    }

    /**
     * 笔宽 → 像素半径。
     *
     * <p>前端的 {@code width} 是 0~400 的抽象量纲，不是像素，所以要按画布尺寸换算。
     * 上下限 [1, 9] 与 JS 一致：小于 1 画不出来，大于 9 会把相邻笔划糊成一片。
     */
    static int brushRadius(double width) {
        double w = width <= 0 ? 4 : width;
        long r = Math.round((w / 400.0) * SIZE * 0.5 + 1);
        return (int) Math.max(1, Math.min(9, r));
    }

    /** 颜色串 → RGB；认不出就用兜底笔色。 */
    static int[] parseColor(String color) {
        if (color == null) {
            return DEFAULT_INK;
        }
        String h = color.trim();
        Matcher m6 = HEX6.matcher(h);
        if (m6.find()) {
            String v = m6.group(1);
            return new int[] {
                    Integer.parseInt(v.substring(0, 2), 16),
                    Integer.parseInt(v.substring(2, 4), 16),
                    Integer.parseInt(v.substring(4, 6), 16)};
        }
        Matcher m3 = HEX3.matcher(h);
        if (m3.find()) {
            String v = m3.group(1);
            return new int[] {
                    Integer.parseInt(v.charAt(0) + "" + v.charAt(0), 16),
                    Integer.parseInt(v.charAt(1) + "" + v.charAt(1), 16),
                    Integer.parseInt(v.charAt(2) + "" + v.charAt(2), 16)};
        }
        Matcher mr = RGB.matcher(h);
        if (mr.find()) {
            return new int[] {
                    Integer.parseInt(mr.group(1)) & 255,
                    Integer.parseInt(mr.group(2)) & 255,
                    Integer.parseInt(mr.group(3)) & 255};
        }
        return DEFAULT_INK;
    }

    private static double num(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return (v == null || !v.isNumber()) ? 0 : v.asDouble();
    }
}
