package net.siniplise.noriosbackend.save;

import java.util.List;
import java.util.Map;
import net.siniplise.noriosbackend.app.SessionRuntime;
import net.siniplise.noriosbackend.config.ConfigLoader;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 存档端点（管理端用）：列槽位、按槽位存一份。
 *
 * <p>放在 `/api/admin/` 下而不是 `/nori-*`：前端那 8 个注入脚本不认识存档，
 * 这些接口是给管理器/排障用的，混进 `/nori-*` 只会让人误以为前端会调。
 *
 * <p>存的内容只包含<b>会话与台词状态</b>；记忆本体在 25503，存档里只留指针
 * （理由见 {@link SaveGame} 的类注释）。
 */
@RestController
@RequestMapping("/api/admin/saves")
public class SaveController {

    private final FileSaveStore store;
    private final SessionRuntime session;
    private final ConfigLoader.Loaded loaded;

    public SaveController(FileSaveStore store, SessionRuntime session, ConfigLoader.Loaded loaded) {
        this.store = store;
        this.session = session;
        this.loaded = loaded;
    }

    /** 槽位概览。 */
    @GetMapping
    public List<FileSaveStore.SlotInfo> list() {
        return store.list();
    }

    /** 读一个槽位（排障用，确认结构）。 */
    @GetMapping("/{slot}")
    public Mono<Map<String, Object>> read(@PathVariable int slot) {
        return Mono.justOrEmpty(store.load(slot))
                .map(game -> Map.<String, Object>of("ok", true, "save", game))
                .defaultIfEmpty(Map.of("ok", false, "reason", "empty-or-corrupt"));
    }

    /** 读档：把槽位里的状态恢复进当前会话。 */
    @PostMapping("/{slot}/load")
    public Mono<Map<String, Object>> load(@PathVariable int slot) {
        var game = store.load(slot);
        if (game.isEmpty()) {
            return Mono.just(Map.of("ok", false, "reason", "empty-or-corrupt"));
        }
        return Mono.fromFuture(session.restoreFrom(game.get()))
                .map(ignored -> Map.<String, Object>of("ok", true, "slot", slot,
                        "savedAt", game.get().savedAt()));
    }

    /** 写一个槽位：从当前会话 + 台词状态现场组一份。 */
    @PostMapping("/{slot}")
    public Mono<Map<String, Object>> save(@PathVariable int slot) {
        return Mono.fromFuture(session.snapshot()).flatMap(life -> Mono
                .fromFuture(session.scriptSnapshot())
                .map(script -> {
                    SaveGame game = SaveGame.of(slot, "0.1.0-SNAPSHOT",
                            loaded.config().upstreams().gameExe(), "unknown", "Nori.Web.noai.exe",
                            new SaveGame.Session(life.phase(), life.cartridge(), life.ended(),
                                    life.seq(), List.copyOf(life.facts())),
                            SaveGame.Script.from(script),
                            new SaveGame.Speech(List.of(), 0),
                            new SaveGame.PersonaRef(loaded.config().upstreams().persona(), "", "记忆本体在人格服务里"));
                    store.save(game);
                    return Map.<String, Object>of("ok", true, "slot", slot, "savedAt", game.savedAt());
                }));
    }
}
