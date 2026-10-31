package net.siniplise.noriosbackend.store;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.siniplise.noriosbackend.config.ConfigLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * SQLite(WAL) 里的台词状态：**单行 JSON**。
 *
 * <h2>为什么单行整存</h2>
 * 状态只有几 KB（14 个 beat）。拆成多表就要处理"三次写入之间的中间态"；
 * 整存成一行 = 一次 UPDATE 天然原子，回滚也简单（换回旧 JSON 就回到旧状态）。
 *
 * <h2>为什么写入异步</h2>
 * {@code save} 在会话线程里被调用（台词一触发就要落盘）。SQLite 的 fsync 哪怕几毫秒，
 * 也会把帧序拖歪——而帧序一歪，客户端听到的就是错位音频。所以：
 * <b>会话线程只更新"最新快照"，写盘在单独线程里做，并合并连续写入</b>。
 *
 * <h2>读坏了不抛</h2>
 * JSON 解析失败返回空状态并喊一声。台词状态坏了最多"这局台词重说一遍"，
 * 远比"整个后端起不来"轻。
 */
@Component
public class SqliteScriptStateStore implements ScriptStateStore {

    private static final Logger log = LoggerFactory.getLogger(SqliteScriptStateStore.class);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final String DDL = """
            CREATE TABLE IF NOT EXISTS script_state (
                id         INTEGER PRIMARY KEY CHECK (id = 1),
                json       TEXT    NOT NULL,
                updated_at INTEGER NOT NULL
            )""";

    private static final String UPSERT = """
            INSERT INTO script_state (id, json, updated_at) VALUES (1, ?, ?)
            ON CONFLICT(id) DO UPDATE SET json = excluded.json, updated_at = excluded.updated_at""";

    private final Path dbPath;
    private final Connection connection;
    private final ExecutorService writer;
    private final AtomicReference<ScriptStateSnapshot> pending = new AtomicReference<>();
    private final AtomicBoolean scheduled = new AtomicBoolean();

    @org.springframework.beans.factory.annotation.Autowired
    public SqliteScriptStateStore(ObjectProvider<ConfigLoader.Loaded> configProvider) {
        this(Path.of(configProvider.getIfAvailable(ConfigLoader::load).config().memory().path()));
    }

    /** 可注入路径（测试用临时文件）。 */
    public SqliteScriptStateStore(Path dbPath) {
        this.dbPath = dbPath;
        try {
            Path parent = dbPath.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            this.connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=NORMAL");
                st.execute(DDL);
            }
        } catch (Exception e) {
            throw new IllegalStateException("打不开状态库: " + dbPath, e);
        }
        this.writer = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "nori-store-writer");
            t.setDaemon(true);
            return t;
        });
        log.info("store: 台词状态库 {}（WAL）", dbPath.toAbsolutePath());
    }

    @Override
    public ScriptStateSnapshot load() {
        synchronized (this) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT json FROM script_state WHERE id = 1");
                 ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return ScriptStateSnapshot.empty();
                }
                return decode(rs.getString(1));
            } catch (SQLException e) {
                log.error("store: 读台词状态失败，按空状态启动: {}", e.toString());
                return ScriptStateSnapshot.empty();
            }
        }
    }

    private ScriptStateSnapshot decode(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            java.util.Map<String, Long> done = new java.util.LinkedHashMap<>();
            root.at("/done").properties().forEach(e -> done.put(e.getKey(), e.getValue().asLong()));
            java.util.Map<String, Integer> rotation = new java.util.LinkedHashMap<>();
            root.at("/rotation").properties().forEach(e -> rotation.put(e.getKey(), e.getValue().asInt()));
            java.util.Set<String> memory = new java.util.LinkedHashSet<>();
            JsonNode memNode = root.get("memoryWritten");
            if (memNode != null && memNode.isArray()) {
                memNode.forEach(n -> {
                    if (n.isString()) {
                        memory.add(n.asString());
                    }
                });
            }
            return ScriptStateSnapshot.of(done, rotation, memory);
        } catch (JacksonException e) {
            log.error("store: 台词状态 JSON 损坏，按空状态启动（这局台词会重说）: {}", e.toString());
            return ScriptStateSnapshot.empty();
        }
    }

    @Override
    public void save(ScriptStateSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        pending.set(snapshot);
        if (scheduled.compareAndSet(false, true)) {
            writer.execute(this::drain);
        }
    }

    /** 合并写：一次落盘只写"目前最新"的那一份，中间态直接丢掉。 */
    private void drain() {
        try {
            ScriptStateSnapshot snapshot = pending.getAndSet(null);
            if (snapshot != null) {
                writeThrough(snapshot);
            }
        } finally {
            scheduled.set(false);
            // 在 drain 期间又来了新的，就再排一次
            if (pending.get() != null && scheduled.compareAndSet(false, true)) {
                writer.execute(this::drain);
            }
        }
    }

    private void writeThrough(ScriptStateSnapshot snapshot) {
        String json = encode(snapshot);
        synchronized (this) {
            try (PreparedStatement ps = connection.prepareStatement(UPSERT)) {
                ps.setString(1, json);
                ps.setLong(2, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (SQLException e) {
                log.error("store: 写台词状态失败（下次触发会重试）: {}", e.toString());
            }
        }
    }

    private String encode(ScriptStateSnapshot snapshot) {
        var root = MAPPER.createObjectNode();
        var done = root.putObject("done");
        snapshot.done().forEach(done::put);
        var rotation = root.putObject("rotation");
        snapshot.rotation().forEach(rotation::put);
        var mem = root.putArray("memoryWritten");
        snapshot.memoryWritten().forEach(mem::add);
        return MAPPER.writeValueAsString(root);
    }

    @Override
    public void flush() {
        if (pending.get() == null && !scheduled.get()) {
            return;
        }
        try {
            // 排一个空任务并等它完成 ⇒ 之前排队的写都已经落盘
            writer.submit(() -> { }).get(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("store: flush 超时/失败: {}", e.toString());
        }
    }

    @Override
    public void close() {
        flush();
        writer.shutdownNow();
        synchronized (this) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // 关不掉就算了，进程要退出了
            }
        }
    }
}
