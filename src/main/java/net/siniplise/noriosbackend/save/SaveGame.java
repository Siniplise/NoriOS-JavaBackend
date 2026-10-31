package net.siniplise.noriosbackend.save;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.siniplise.noriosbackend.store.ScriptStateSnapshot;

/**
 * 存档结构（`data/saves/slot-NN.json`）。
 *
 * <h2>设计原则</h2>
 * <ol>
 *   <li><b>第一行就是版本号</b>：{@code schemaVersion}。以后改结构必须先能读出旧档，
 *       没有版本号的存档迟早会变成"只能删掉重开"；</li>
 *   <li><b>按归属分节</b>：每一节只有一个写入者，改结构时只动自己那节；</li>
 *   <li><b>记忆不复制</b>：记忆本体在 25503（`nori_llm_state.json`，427KB）。
 *       存档只留 <b>指针 + 摘要</b>——复制一份就会出现"两份记忆谁为准"的问题；</li>
 *   <li><b>facts 保持顺序</b>：`LinkedHashSet` 的插入序就是游戏事实的到达序，
 *       重排会让"先解锁后记忆"的判断错位。</li>
 * </ol>
 *
 * <h2>字段</h2>
 * <pre>
 * schemaVersion  1
 * slot           1..maxSlots
 * savedAt        ISO-8601（带时区）
 * app            写入者版本（排查"这档是哪版存的"）
 * game           上游实物标识：bundle md5 + exe 名（换版本后行为会变）
 * session        phase / cartridge / ended / seq / facts[]
 * script         台词闸门与轮换（done / rotation / memoryWritten）
 * speech         待播队列 + epoch（停机时队列不该丢）
 * persona        指向 25503 的指针：provider / stateDigest / note
 * </pre>
 */
public record SaveGame(
        int schemaVersion,
        int slot,
        String savedAt,
        App app,
        GameVersion game,
        Session session,
        Script script,
        Speech speech,
        PersonaRef persona) {

    /** 当前结构版本。加字段不涨版本；改语义/删字段才涨。 */
    public static final int CURRENT_SCHEMA = 1;

    /** 写入者标识。 */
    public record App(String name, String version) {
    }

    /**
     * 上游实物：换 bundle / 换 exe / 换上游地址，行为都可能不同，存档必须记下来。
     *
     * <p>{@code bundle} 是前端打包产物的指纹（`NormalApp-*.js` 的 md5 前 10 位）。
     * 启动时不做探测（那会给"能起服务"凭空加一个对上游的依赖），所以这里可能是
     * {@code "unknown"}——**宁可写 unknown，也不要把上游 URL 塞进这个字段**（语义混了比空着糟）。
     */
    public record GameVersion(String upstream, String bundle, String exe) {
    }

    /** 会话状态。 */
    public record Session(String phase, String cartridge, boolean ended, long seq, List<String> facts) {
        public Session {
            facts = facts == null ? List.of() : List.copyOf(facts);
        }
    }

    /** 台词状态（与 {@link ScriptStateSnapshot} 同构）。 */
    public record Script(Map<String, Long> done, Map<String, Integer> rotation,
                         Set<String> memoryWritten) {
        public Script {
            done = done == null ? Map.of() : new LinkedHashMap<>(done);
            rotation = rotation == null ? Map.of() : new LinkedHashMap<>(rotation);
            memoryWritten = memoryWritten == null ? Set.of() : Set.copyOf(memoryWritten);
        }

        public static Script from(ScriptStateSnapshot snapshot) {
            return new Script(snapshot.done(), snapshot.rotation(), snapshot.memoryWritten());
        }

        public ScriptStateSnapshot toSnapshot() {
            return ScriptStateSnapshot.of(done, rotation, memoryWritten);
        }
    }

    /** 待播台词（存档时还在队列里的话）。 */
    public record Speech(List<PendingLine> pending, long epoch) {
        public Speech {
            pending = pending == null ? List.of() : List.copyOf(pending);
        }

        public record PendingLine(String qid, int qpart, int qof, String text, String emotion) {
        }
    }

    /** 记忆在 25503，这里只留指针。 */
    public record PersonaRef(String provider, String stateDigest, String note) {
    }

    /** 新档。 */
    public static SaveGame of(int slot, String appVersion, String upstream, String bundle, String exe,
                              Session session, Script script, Speech speech, PersonaRef persona) {
        return new SaveGame(CURRENT_SCHEMA, slot, Instant.now().toString(),
                new App("nori-backend", appVersion), new GameVersion(upstream, bundle, exe),
                session, script, speech, persona);
    }

    /** 结构是否可读（版本比当前新就不认识）。 */
    public boolean readableByThisVersion() {
        return schemaVersion >= 1 && schemaVersion <= CURRENT_SCHEMA;
    }
}
