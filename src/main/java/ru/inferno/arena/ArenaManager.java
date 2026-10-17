package ru.inferno.arena;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.session.ClipboardHolder;
import com.sk89q.worldedit.world.block.BlockTypes;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Вся логика Небесной Арены Инферно:
 * расписание (18:00 МСК) -> вставка схематики -> запертый шулкер (5 мин) -> открыт (ещё 20 мин) -> удаление.
 */
public final class ArenaManager {

    public enum Phase { IDLE, LOCKED, OPEN }

    private final InfernoArena plugin;
    private final NamespacedKey keyGuard;
    private final NamespacedKey keyPhase;
    private final NamespacedKey keyHolo;

    private Phase phase = Phase.IDLE;
    private World world;
    private int px, py, pz;                       // координаты главного приза (шулкера)
    private long spawnedAt, openAt, endAt;
    private String lastTrigger = "";

    // регион схематики
    private int mnX, mnY, mnZ, sx, sy, sz;
    private double rx0, ry0, rz0, rx1, ry1, rz1;  // весь регион [min, max)
    private double hx0, hz0, hx1, hz1, floorY;    // внутренний зал

    private BossBar bar;
    private final List<UUID> holoIds = new ArrayList<>();
    private final Set<UUID> guards = new HashSet<>();
    private int guardsTotal;
    private Inventory prizeInv;
    private BukkitTask tickTask, guardTask;
    private final Set<String> alertsFired = new HashSet<>();
    private final Map<UUID, Location> lastInside = new HashMap<>();
    private final Random rnd = new Random();

    public ArenaManager(InfernoArena plugin) {
        this.plugin = plugin;
        this.keyGuard = new NamespacedKey(plugin, "inferno_guard");
        this.keyPhase = new NamespacedKey(plugin, "inferno_phase");
        this.keyHolo = new NamespacedKey(plugin, "inferno_holo");
    }

    // =====================================================================================
    //  Жизненный цикл плагина
    // =====================================================================================

    public void start() {
        loadState();
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
        guardTask = Bukkit.getScheduler().runTaskTimer(plugin, this::guardTick, 10L, 10L);
    }

    public void shutdown() {
        if (tickTask != null) tickTask.cancel();
        if (guardTask != null) guardTask.cancel();
        saveState();
        if (bar != null) bar.removeAll();
        removeHolos();
    }

    public void reloadSettings() {
        if (phase != Phase.IDLE) recalc();
    }

    private FileConfiguration cfg() {
        return plugin.getConfig();
    }

    // =====================================================================================
    //  Сообщения
    // =====================================================================================

    public static String c(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    public String m(String key, Object... kv) {
        String s = cfg().getString("messages." + key, key);
        return fill(s, kv);
    }

    private List<String> ml(String key, Object... kv) {
        List<String> out = new ArrayList<>();
        for (String s : cfg().getStringList("messages." + key)) out.add(fill(s, kv));
        return out;
    }

    private String fill(String s, Object... kv) {
        s = s.replace("{prefix}", cfg().getString("messages.prefix", ""));
        s = s.replace("{name}", cfg().getString("messages.arena-name", "Arena"));
        for (int i = 0; i + 1 < kv.length; i += 2) {
            s = s.replace("{" + kv[i] + "}", String.valueOf(kv[i + 1]));
        }
        return c(s);
    }

    private void broadcast(List<String> lines) {
        for (String l : lines) Bukkit.broadcastMessage(l);
    }

    static String mmss(long sec) {
        sec = Math.max(0, sec);
        return String.format("%02d:%02d", sec / 60, sec % 60);
    }

    static String hm(long sec) {
        sec = Math.max(0, sec);
        long h = sec / 3600, mi = (sec % 3600) / 60;
        if (h > 0) return h + "ч " + mi + "м";
        if (mi > 0) return mi + "м " + (sec % 60) + "с";
        return sec + "с";
    }

    // =====================================================================================
    //  Расписание (МСК)
    // =====================================================================================

    private ZoneId zone() {
        try {
            return ZoneId.of(cfg().getString("schedule.timezone", "Europe/Moscow"));
        } catch (Exception e) {
            return ZoneId.of("Europe/Moscow");
        }
    }

    private List<LocalTime> times() {
        List<LocalTime> out = new ArrayList<>();
        for (String s : cfg().getStringList("schedule.times")) {
            try {
                out.add(LocalTime.parse(s.trim()));
            } catch (Exception ignored) {
                plugin.getLogger().warning("Неверное время в schedule.times: " + s);
            }
        }
        if (out.isEmpty()) out.add(LocalTime.of(18, 0));
        return out;
    }

    private ZonedDateTime nextSpawn(ZonedDateTime now) {
        ZonedDateTime best = null;
        for (LocalTime t : times()) {
            for (int add = 0; add <= 1; add++) {
                ZonedDateTime cand = now.toLocalDate().plusDays(add).atTime(t).atZone(zone());
                if (cand.isAfter(now) && (best == null || cand.isBefore(best))) best = cand;
            }
        }
        return best;
    }

    private void scheduleTick() {
        ZoneId z = zone();
        ZonedDateTime now = ZonedDateTime.now(z);

        for (LocalTime t : times()) {
            ZonedDateTime trig = now.toLocalDate().atTime(t).atZone(z);
            String key = trig.toLocalDate() + " " + t;
            long late = Duration.between(trig, now).getSeconds();
            if (late >= 0 && late <= 120 && !key.equals(lastTrigger)) {
                lastTrigger = key;
                saveState();
                String err = startEvent(null);
                if (err != null) plugin.getLogger().severe("Не удалось запустить Арену: " + err);
                return;
            }
        }

        ZonedDateTime next = nextSpawn(now);
        if (next == null) return;
        long diff = Duration.between(now, next).getSeconds();
        for (int minutes : cfg().getIntegerList("schedule.alert-minutes")) {
            long target = minutes * 60L;
            String key = next + "|" + minutes;
            if (diff <= target && diff > target - 10 && alertsFired.add(key)) {
                String when = minutes >= 60 ? (minutes / 60) + " ч" : minutes + " мин";
                Bukkit.broadcastMessage(m("alert", "time", when));
                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.playSound(p.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 0.6f, 0.5f);
                }
            }
        }
    }

    // =====================================================================================
    //  Информация для /arena
    // =====================================================================================

    public void sendInfo(CommandSender s) {
        String status;
        long now = System.currentTimeMillis();
        ZonedDateTime znow = ZonedDateTime.now(zone());
        ZonedDateTime next = nextSpawn(znow);
        String nextStr = next == null ? "?" : next.format(DateTimeFormatter.ofPattern("HH:mm")) + " МСК";
        String until = next == null ? "?" : hm(Duration.between(znow, next).getSeconds());

        switch (phase) {
            case LOCKED:
                status = m("status-locked", "x", px, "y", py, "z", pz, "world", world.getName(),
                        "time", mmss((openAt - now) / 1000));
                break;
            case OPEN:
                status = m("status-open", "x", px, "y", py, "z", pz, "world", world.getName(),
                        "time", mmss((endAt - now) / 1000));
                break;
            default:
                status = m("status-idle", "next", nextStr, "until", until);
        }

        for (String line : ml("info",
                "status", status,
                "next", nextStr,
                "until", until,
                "opening", cfg().getInt("timers.opening-minutes", 5),
                "close", cfg().getInt("timers.close-after-open-minutes", 20),
                "guards", cfg().getInt("guards.count", 8))) {
            s.sendMessage(line);
        }
        if (s.hasPermission(InfernoArena.ADMIN_PERM)) {
            for (String line : ml("info-admin")) s.sendMessage(line);
        }
    }

    // =====================================================================================
    //  Запуск ивента
    // =====================================================================================

    /** @return null если успешно, иначе текст ошибки. */
    public String startEvent(Location here) {
        if (phase != Phase.IDLE) return "Арена уже активна.";
        if (!weAvailable()) return "Не найден WorldEdit/FastAsyncWorldEdit.";

        World w;
        int x, y, z;
        if (here != null && here.getWorld() != null) {
            w = here.getWorld();
            x = here.getBlockX();
            y = here.getBlockY();
            z = here.getBlockZ();
        } else {
            w = Bukkit.getWorld(cfg().getString("spawn.world", "world"));
            if (w == null) return "Мир '" + cfg().getString("spawn.world") + "' не найден.";
            y = cfg().getInt("spawn.y", 112);
            if ("static".equalsIgnoreCase(cfg().getString("spawn.mode", "random"))) {
                x = cfg().getInt("spawn.static.x", 0);
                z = cfg().getInt("spawn.static.z", 0);
            } else {
                int min = cfg().getInt("spawn.radius-min", -5000);
                int max = cfg().getInt("spawn.radius-max", 5000);
                int tx = 0, tz = 0;
                for (int i = 0; i < 25; i++) {
                    tx = min + rnd.nextInt(Math.max(1, max - min + 1));
                    tz = min + rnd.nextInt(Math.max(1, max - min + 1));
                    if (w.getWorldBorder().isInside(new Location(w, tx, y, tz))) break;
                }
                x = tx;
                z = tz;
            }
        }

        this.world = w;
        this.px = x;
        this.py = y;
        this.pz = z;
        recalc();

        if (mnY < w.getMinHeight() || mnY + sy > w.getMaxHeight()) {
            world = null;
            return "Высота арены выходит за пределы мира (spawn.y = " + y + ").";
        }

        try {
            forceLoad(true);
            paste(w);
        } catch (Throwable t) {
            plugin.getLogger().severe("Ошибка вставки схематики: " + t);
            t.printStackTrace();
            forceLoad(false);
            world = null;
            return "Ошибка вставки схематики: " + t.getMessage();
        }

        Material locked = mat(cfg().getString("arena.chest-material-locked"), Material.ORANGE_SHULKER_BOX);
        w.getBlockAt(px, py, pz).setType(locked, false);

        long now = System.currentTimeMillis();
        phase = Phase.LOCKED;
        spawnedAt = now;
        openAt = now + cfg().getInt("timers.opening-minutes", 5) * 60_000L;
        endAt = 0;
        alertsFired.clear();
        prizeInv = null;

        createBar();
        spawnHolos();
        Bukkit.getScheduler().runTaskLater(plugin, this::spawnGuards, 40L);
        saveState();

        broadcast(ml("spawn", "x", px, "y", py, "z", pz, "world", w.getName(),
                "opening", cfg().getInt("timers.opening-minutes", 5),
                "close", cfg().getInt("timers.close-after-open-minutes", 20),
                "guards", cfg().getInt("guards.count", 8)));
        Location center = new Location(w, px + 0.5, py + 1, pz + 0.5);
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.7f, 0.6f);
        }
        for (int i = 0; i < 3; i++) w.strikeLightningEffect(center.clone().add(rnd.nextInt(9) - 4, 0, rnd.nextInt(9) - 4));
        return null;
    }

    public boolean stopEvent() {
        if (phase == Phase.IDLE) return false;
        removeArena(true);
        return true;
    }

    public boolean teleportTo(Player p) {
        if (phase == Phase.IDLE || world == null) return false;
        p.teleport(safeAbove(world, px, py, pz + 7));
        return true;
    }

    private boolean weAvailable() {
        try {
            Class.forName("com.sk89q.worldedit.WorldEdit");
            return Bukkit.getPluginManager().getPlugin("WorldEdit") != null
                    || Bukkit.getPluginManager().getPlugin("FastAsyncWorldEdit") != null;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private Material mat(String name, Material def) {
        if (name == null) return def;
        Material m = Material.matchMaterial(name);
        return m == null ? def : m;
    }

    // =====================================================================================
    //  Регион и WorldEdit
    // =====================================================================================

    private int[] triple(String path, int a, int b, int c) {
        List<Integer> l = cfg().getIntegerList(path);
        if (l.size() < 3) return new int[]{a, b, c};
        return new int[]{l.get(0), l.get(1), l.get(2)};
    }

    private void recalc() {
        int[] off = triple("arena.prize-offset", 42, 7, 55);
        int[] size = triple("arena.size", 85, 28, 115);
        int inset = cfg().getInt("arena.hall-inset", 11);
        mnX = px - off[0];
        mnY = py - off[1];
        mnZ = pz - off[2];
        sx = size[0];
        sy = size[1];
        sz = size[2];
        rx0 = mnX;
        ry0 = mnY;
        rz0 = mnZ;
        rx1 = mnX + sx;
        ry1 = mnY + sy;
        rz1 = mnZ + sz;
        hx0 = rx0 + inset;
        hz0 = rz0 + inset;
        hx1 = rx1 - inset;
        hz1 = rz1 - inset;
        floorY = mnY + 4;
    }

    private void forceLoad(boolean on) {
        if (world == null) return;
        for (int cx = mnX >> 4; cx <= (mnX + sx - 1) >> 4; cx++) {
            for (int cz = mnZ >> 4; cz <= (mnZ + sz - 1) >> 4; cz++) {
                world.setChunkForceLoaded(cx, cz, on);
                if (on) world.getChunkAt(cx, cz).load(true);
            }
        }
    }

    private void paste(World w) throws Exception {
        File f = new File(plugin.getDataFolder(), cfg().getString("arena.schematic", "inferno_sky_arena.schem"));
        ClipboardFormat fmt = ClipboardFormats.findByFile(f);
        if (fmt == null) throw new IOException("Неизвестный формат схематики: " + f.getName());
        Clipboard cb;
        try (FileInputStream in = new FileInputStream(f); ClipboardReader r = fmt.getReader(in)) {
            cb = r.read();
        }
        BlockVector3 min = cb.getMinimumPoint();
        BlockVector3 origin = cb.getOrigin();
        // Не зависим от Offset в файле: ставим минимальный угол схематики ровно в (mnX, mnY, mnZ).
        BlockVector3 target = BlockVector3.at(mnX, mnY, mnZ).add(origin.subtract(min));
        try (EditSession es = WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(w))) {
            Operation op = new ClipboardHolder(cb).createPaste(es).to(target).ignoreAirBlocks(false).build();
            Operations.complete(op);
        }
    }

    private void clearRegion(World w) throws Exception {
        CuboidRegion region = new CuboidRegion(BukkitAdapter.adapt(w),
                BlockVector3.at(mnX, mnY, mnZ),
                BlockVector3.at(mnX + sx - 1, mnY + sy - 1, mnZ + sz - 1));
        try (EditSession es = WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(w))) {
            es.setBlocks((Region) region, BlockTypes.AIR.getDefaultState());
        }
    }

    public boolean inArena(Location l) {
        return phase != Phase.IDLE && world != null && l.getWorld() != null && l.getWorld().equals(world)
                && l.getX() >= rx0 && l.getX() < rx1
                && l.getY() >= ry0 && l.getY() < ry1
                && l.getZ() >= rz0 && l.getZ() < rz1;
    }

    // =====================================================================================
    //  Тики
    // =====================================================================================

    private void tick() {
        if (phase == Phase.IDLE) {
            scheduleTick();
            return;
        }
        long now = System.currentTimeMillis();
        if (phase == Phase.LOCKED && now >= openAt) {
            unlock();
        }
        if (phase == Phase.OPEN && now >= endAt) {
            removeArena(true);
            return;
        }
        updateBar(now);
        updateHolos(now);
        ambient();
    }

    private void ambient() {
        if (world == null) return;
        Location l = new Location(world, px + 0.5, py + 1.3, pz + 0.5);
        world.spawnParticle(Particle.SOUL_FIRE_FLAME, l, 6, 0.5, 0.3, 0.5, 0.01);
        world.spawnParticle(Particle.LAVA, l, 2, 0.8, 0.2, 0.8, 0);
        if (phase == Phase.OPEN) {
            world.spawnParticle(Particle.END_ROD, l, 3, 0.4, 0.6, 0.4, 0.02);
        }
        if (rnd.nextInt(25) == 0) {
            world.playSound(l, Sound.AMBIENT_NETHER_WASTES_MOOD, 2.0f, 0.6f);
        }
    }

    // =====================================================================================
    //  Главный приз
    // =====================================================================================

    public boolean isPrize(Block b) {
        return phase != Phase.IDLE && world != null && b.getWorld().equals(world)
                && b.getX() == px && b.getY() == py && b.getZ() == pz;
    }

    public void onPrizeClick(Player p) {
        long now = System.currentTimeMillis();
        if (phase == Phase.LOCKED) {
            p.sendMessage(m("prize-locked", "time", mmss((openAt - now) / 1000)));
            p.playSound(p.getLocation(), Sound.BLOCK_CHEST_LOCKED, 1f, 0.6f);
        } else if (phase == Phase.OPEN) {
            if (prizeInv == null) prizeInv = buildPrize();
            p.openInventory(prizeInv);
            p.playSound(p.getLocation(), Sound.BLOCK_SHULKER_BOX_OPEN, 1f, 0.7f);
        }
    }

    private void unlock() {
        phase = Phase.OPEN;
        endAt = System.currentTimeMillis() + cfg().getInt("timers.close-after-open-minutes", 20) * 60_000L;
        Material open = mat(cfg().getString("arena.chest-material-unlocked"), Material.RED_SHULKER_BOX);
        world.getBlockAt(px, py, pz).setType(open, false);
        prizeInv = buildPrize();
        if (bar != null) bar.setColor(BarColor.PURPLE);
        saveState();

        Bukkit.broadcastMessage(m("opened", "x", px, "y", py, "z", pz,
                "close", cfg().getInt("timers.close-after-open-minutes", 20)));
        Location l = new Location(world, px + 0.5, py + 1, pz + 0.5);
        world.strikeLightningEffect(l);
        world.spawnParticle(Particle.EXPLOSION_LARGE, l, 3, 1, 0.5, 1, 0);
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.playSound(p.getLocation(), Sound.BLOCK_END_PORTAL_SPAWN, 0.6f, 0.8f);
        }
    }

    /** Лут берётся из секции inv.<id> конфига ровно в том формате, что был в старом конфиге. */
    private Inventory buildPrize() {
        String title = c(cfg().getString("arena.inv-name", "Inferno"));
        int size = cfg().getInt("arena.inv-size", 54);
        Inventory inv = Bukkit.createInventory(null, size, title);
        ConfigurationSection sec = cfg().getConfigurationSection("inv." + cfg().getString("arena.inv-id", "inv21499"));
        if (sec == null) return inv;
        for (String slotKey : sec.getKeys(false)) {
            ConfigurationSection s = sec.getConfigurationSection(slotKey);
            if (s == null) continue;
            int slot;
            try {
                slot = Integer.parseInt(slotKey);
            } catch (NumberFormatException e) {
                continue;
            }
            for (String chanceKey : s.getKeys(false)) {
                double chance;
                try {
                    chance = Double.parseDouble(chanceKey);
                } catch (NumberFormatException e) {
                    continue;
                }
                if (ThreadLocalRandom.current().nextDouble() * 100.0 < chance) {
                    ItemStack it = s.getItemStack(chanceKey);
                    if (it != null && slot >= 0 && slot < size) {
                        inv.setItem(slot, it);
                        break;
                    }
                }
            }
        }
        return inv;
    }

    // =====================================================================================
    //  Голограммы и боссбар
    // =====================================================================================

    private void spawnHolos() {
        removeHolos();
        List<String> lines = cfg().getStringList("hologram.locked");
        double bx = px + cfg().getDouble("hologram.offset-x", 0.5);
        double by = py + cfg().getDouble("hologram.offset-y", 2.5);
        double bz = pz + cfg().getDouble("hologram.offset-z", 0.5);
        for (int i = 0; i < lines.size(); i++) {
            Location l = new Location(world, bx, by - i * 0.3, bz);
            ArmorStand as = world.spawn(l, ArmorStand.class, a -> {
                a.setVisible(false);
                a.setMarker(true);
                a.setGravity(false);
                a.setInvulnerable(true);
                a.setSmall(true);
                a.setCustomNameVisible(true);
                a.setPersistent(false);
                a.getPersistentDataContainer().set(keyHolo, PersistentDataType.BYTE, (byte) 1);
            });
            holoIds.add(as.getUniqueId());
        }
        updateHolos(System.currentTimeMillis());
    }

    private void updateHolos(long now) {
        List<String> lines = cfg().getStringList(phase == Phase.OPEN ? "hologram.open" : "hologram.locked");
        long target = phase == Phase.OPEN ? endAt : openAt;
        String time = mmss((target - now) / 1000);
        for (int i = 0; i < holoIds.size() && i < lines.size(); i++) {
            Entity e = Bukkit.getEntity(holoIds.get(i));
            if (e != null) e.setCustomName(fill(lines.get(i), "time", time));
        }
    }

    private void removeHolos() {
        for (UUID id : holoIds) {
            Entity e = Bukkit.getEntity(id);
            if (e != null) e.remove();
        }
        holoIds.clear();
        if (world != null) {
            for (Entity e : world.getEntitiesByClass(ArmorStand.class)) {
                if (e.getPersistentDataContainer().has(keyHolo, PersistentDataType.BYTE)) e.remove();
            }
        }
    }

    private void createBar() {
        if (bar != null) bar.removeAll();
        bar = Bukkit.createBossBar("", BarColor.RED, BarStyle.SEGMENTED_10);
        bar.setVisible(true);
        for (Player p : Bukkit.getOnlinePlayers()) bar.addPlayer(p);
        updateBar(System.currentTimeMillis());
    }

    public void addPlayer(Player p) {
        if (bar != null) bar.addPlayer(p);
    }

    private void updateBar(long now) {
        if (bar == null) return;
        boolean locked = phase == Phase.LOCKED;
        long target = locked ? openAt : endAt;
        long total = locked ? Math.max(1, openAt - spawnedAt)
                : cfg().getInt("timers.close-after-open-minutes", 20) * 60_000L;
        long rem = Math.max(0, target - now);
        bar.setProgress(Math.min(1.0, Math.max(0.0, rem / (double) total)));
        bar.setColor(locked ? BarColor.RED : BarColor.PURPLE);
        bar.setTitle(m(locked ? "bossbar-locked" : "bossbar-open",
                "x", px, "y", py, "z", pz,
                "time", mmss(rem / 1000),
                "guards", guards.size() + "/" + guardsTotal));
    }

    // =====================================================================================
    //  Стражи Инферно
    // =====================================================================================

    private void spawnGuards() {
        if (phase == Phase.IDLE || world == null) return;
        int n = cfg().getInt("guards.count", 8);
        double radius = cfg().getDouble("guards.ring-radius", 14);
        guardsTotal = n;
        for (int i = 0; i < n; i++) {
            double ang = 2 * Math.PI * i / n;
            Location l = findGuardSpawn(ang, radius);
            spawnGuard(l);
        }
        updateBar(System.currentTimeMillis());
    }

    private Location findGuardSpawn(double ang, double radius) {
        double[] radii = {radius, radius - 2, radius + 2, radius - 4, radius + 4, 8};
        for (double r : radii) {
            double x = px + 0.5 + Math.cos(ang) * r;
            double z = pz + 0.5 + Math.sin(ang) * r;
            for (int dy = 0; dy < 4; dy++) {
                int by = (int) Math.floor(floorY) + dy;
                Block feet = world.getBlockAt((int) Math.floor(x), by, (int) Math.floor(z));
                Block head = feet.getRelative(0, 1, 0);
                Block below = feet.getRelative(0, -1, 0);
                if (feet.isPassable() && head.isPassable() && !below.isPassable()
                        && feet.getType() != Material.LAVA && below.getType() != Material.LAVA) {
                    return new Location(world, x, by, z);
                }
            }
        }
        return new Location(world, px + 0.5, py + 1, pz + 4.5);
    }

    private ItemStack armorPiece(Material m) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.addEnchant(Enchantment.PROTECTION_ENVIRONMENTAL, 4, true);
        it.setItemMeta(meta);
        return it;
    }

    private void spawnGuard(Location loc) {
        EntityType type;
        try {
            type = EntityType.valueOf(cfg().getString("guards.entity-type", "WITHER_SKELETON").toUpperCase());
        } catch (IllegalArgumentException e) {
            type = EntityType.WITHER_SKELETON;
        }
        Entity ent = world.spawnEntity(loc, type);
        if (!(ent instanceof LivingEntity le)) {
            ent.remove();
            return;
        }
        le.setCustomName(c(cfg().getString("guards.name-phase1", "&4&lСтраж Инферно")));
        le.setCustomNameVisible(true);
        le.setRemoveWhenFarAway(false);
        le.setPersistent(true);
        le.setCanPickupItems(false);

        double hp = cfg().getDouble("guards.phase1.health", 80);
        setAttr(le, Attribute.GENERIC_MAX_HEALTH, hp);
        le.setHealth(hp);
        setAttr(le, Attribute.GENERIC_ATTACK_DAMAGE, cfg().getDouble("guards.phase1.damage", 8));
        setAttr(le, Attribute.GENERIC_FOLLOW_RANGE, 48);
        setAttr(le, Attribute.GENERIC_KNOCKBACK_RESISTANCE, 0.5);

        EntityEquipment eq = le.getEquipment();
        if (eq != null) {
            eq.setHelmet(armorPiece(Material.NETHERITE_HELMET));
            eq.setChestplate(armorPiece(Material.NETHERITE_CHESTPLATE));
            eq.setLeggings(armorPiece(Material.NETHERITE_LEGGINGS));
            eq.setBoots(armorPiece(Material.NETHERITE_BOOTS));

            ItemStack sword = new ItemStack(Material.NETHERITE_SWORD);
            ItemMeta sm = sword.getItemMeta();
            sm.addEnchant(Enchantment.DAMAGE_ALL, 5, true);
            sm.addEnchant(Enchantment.FIRE_ASPECT, 2, true);
            sword.setItemMeta(sm);
            eq.setItemInMainHand(sword);
            eq.setItemInOffHand(new ItemStack(Material.TOTEM_OF_UNDYING));

            eq.setHelmetDropChance(0f);
            eq.setChestplateDropChance(0f);
            eq.setLeggingsDropChance(0f);
            eq.setBootsDropChance(0f);
            eq.setItemInMainHandDropChance(0f);
            eq.setItemInOffHandDropChance(0f);
        }

        le.getPersistentDataContainer().set(keyGuard, PersistentDataType.BYTE, (byte) 1);
        le.getPersistentDataContainer().set(keyPhase, PersistentDataType.INTEGER, 1);
        guards.add(le.getUniqueId());

        world.spawnParticle(Particle.SOUL_FIRE_FLAME, loc.clone().add(0, 1, 0), 40, 0.4, 0.8, 0.4, 0.05);
        world.playSound(loc, Sound.ENTITY_WITHER_AMBIENT, 1.0f, 0.5f);
    }

    private void setAttr(LivingEntity le, Attribute a, double v) {
        AttributeInstance ai = le.getAttribute(a);
        if (ai != null) ai.setBaseValue(v);
    }

    public boolean isGuard(Entity e) {
        return e != null && e.getPersistentDataContainer().has(keyGuard, PersistentDataType.BYTE);
    }

    public int phaseOf(Entity e) {
        Integer p = e.getPersistentDataContainer().get(keyPhase, PersistentDataType.INTEGER);
        return p == null ? 1 : p;
    }

    public double damageFor(LivingEntity le) {
        return phaseOf(le) >= 2
                ? cfg().getDouble("guards.phase2.damage", 10)
                : cfg().getDouble("guards.phase1.damage", 8);
    }

    public int guardExp() {
        return cfg().getInt("guards.exp", 40);
    }

    /** «Лопнул» тотем: страж теряет его и становится сильнее (60 HP, урон 10). */
    public void enrage(LivingEntity le) {
        le.getPersistentDataContainer().set(keyPhase, PersistentDataType.INTEGER, 2);
        EntityEquipment eq = le.getEquipment();
        if (eq != null) eq.setItemInOffHand(new ItemStack(Material.AIR));

        double hp = cfg().getDouble("guards.phase2.health", 60);
        setAttr(le, Attribute.GENERIC_MAX_HEALTH, hp);
        setAttr(le, Attribute.GENERIC_ATTACK_DAMAGE, cfg().getDouble("guards.phase2.damage", 10));
        le.setHealth(hp);
        le.setCustomName(c(cfg().getString("guards.name-phase2", "&4&l&nРазъярённый Страж")));
        le.setNoDamageTicks(20);
        le.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 20 * 600, 0, false, false));

        Location l = le.getLocation().add(0, 1, 0);
        World w = le.getWorld();
        w.playSound(l, Sound.ITEM_TOTEM_USE, 1.6f, 0.6f);
        w.playSound(l, Sound.ENTITY_WITHER_HURT, 1.2f, 0.5f);
        w.spawnParticle(Particle.TOTEM, l, 80, 0.4, 0.8, 0.4, 0.5);
        w.spawnParticle(Particle.FLAME, l, 50, 0.5, 0.9, 0.5, 0.08);
        w.spawnParticle(Particle.SOUL, l, 30, 0.5, 0.9, 0.5, 0.05);
        for (Entity near : w.getNearbyEntities(l, 30, 20, 30)) {
            if (near instanceof Player p) p.sendMessage(m("guard-enrage"));
        }
    }

    public void guardDied(LivingEntity le) {
        guards.remove(le.getUniqueId());
        updateBar(System.currentTimeMillis());
        Location l = le.getLocation().add(0, 1, 0);
        le.getWorld().spawnParticle(Particle.SOUL, l, 40, 0.4, 0.8, 0.4, 0.05);
        if (guards.isEmpty() && guardsTotal > 0 && phase != Phase.IDLE) {
            Bukkit.broadcastMessage(m("guards-dead"));
        }
    }

    private void killGuards() {
        for (UUID id : new ArrayList<>(guards)) {
            Entity e = Bukkit.getEntity(id);
            if (e != null) e.remove();
        }
        guards.clear();
        if (world != null) {
            for (Entity e : world.getLivingEntities()) {
                if (isGuard(e)) e.remove();
            }
        }
        guardsTotal = 0;
    }

    /** Раз в 0.5 сек: не даём стражам выйти за границы зала и упасть вниз. */
    private void guardTick() {
        if (phase == Phase.IDLE || world == null) return;

        if (cfg().getBoolean("border.keep-guards-inside", true)) {
            for (UUID id : new ArrayList<>(guards)) {
                Entity ent = Bukkit.getEntity(id);
                if (!(ent instanceof LivingEntity le) || le.isDead()) {
                    if (ent == null) continue;      // чанк выгружен - оставляем
                    guards.remove(id);
                    continue;
                }
                Location l = le.getLocation();
                boolean outside = l.getX() < hx0 + 0.5 || l.getX() > hx1 - 0.5
                        || l.getZ() < hz0 + 0.5 || l.getZ() > hz1 - 0.5
                        || l.getY() < floorY - 1 || l.getY() > ry1;
                if (outside) {
                    double cx = Math.min(hx1 - 1.5, Math.max(hx0 + 1.5, l.getX()));
                    double cz = Math.min(hz1 - 1.5, Math.max(hz0 + 1.5, l.getZ()));
                    Location to = safeAbove(world, (int) Math.floor(cx), (int) floorY, (int) Math.floor(cz));
                    to.setYaw(l.getYaw());
                    to.setPitch(l.getPitch());
                    le.setFallDistance(0f);
                    le.teleport(to);
                    le.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
                    if (le instanceof Mob mob && mob.getTarget() != null && !inArena(mob.getTarget().getLocation())) {
                        mob.setTarget(null);
                    }
                }
            }
        }

        if (cfg().getBoolean("border.keep-players-inside", false)) {
            for (Player p : world.getPlayers()) {
                if (p.hasPermission(InfernoArena.ADMIN_PERM) && p.getGameMode() == org.bukkit.GameMode.CREATIVE) continue;
                Location l = p.getLocation();
                if (inArena(l)) {
                    lastInside.put(p.getUniqueId(), l.clone());
                } else if (lastInside.containsKey(p.getUniqueId())) {
                    p.teleport(lastInside.get(p.getUniqueId()));
                    p.setFallDistance(0f);
                    p.sendMessage(m("border-players"));
                }
            }
        }
    }

    // =====================================================================================
    //  Удаление арены
    // =====================================================================================

    private void removeArena(boolean announce) {
        if (phase == Phase.IDLE && world == null) return;
        World w = world;

        killGuards();
        removeHolos();
        if (prizeInv != null) {
            for (HumanEntity v : new ArrayList<>(prizeInv.getViewers())) v.closeInventory();
            prizeInv = null;
        }
        if (bar != null) {
            bar.removeAll();
            bar = null;
        }

        if (w != null) {
            int secs = cfg().getInt("timers.remove-slow-falling-seconds", 30);
            for (Player p : w.getPlayers()) {
                if (inArena(p.getLocation())) {
                    p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 20 * secs, 0, false, false, true));
                }
            }
            try {
                clearRegion(w);
            } catch (Throwable t) {
                plugin.getLogger().severe("Не удалось очистить регион арены: " + t);
                t.printStackTrace();
            }
            BoundingBox box = new BoundingBox(rx0, ry0, rz0, rx1, ry1, rz1);
            for (Entity e : w.getNearbyEntities(box)) {
                if (e instanceof Item) e.remove();
            }
            w.spawnParticle(Particle.EXPLOSION_HUGE, px + 0.5, py + 1, pz + 0.5, 5, 3, 2, 3, 0);
            forceLoad(false);
        }

        lastInside.clear();
        phase = Phase.IDLE;
        spawnedAt = openAt = endAt = 0;
        world = null;
        saveState();

        if (announce) {
            Bukkit.broadcastMessage(m("ended"));
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.playSound(p.getLocation(), Sound.ENTITY_WITHER_DEATH, 0.5f, 0.5f);
            }
        }
    }

    // =====================================================================================
    //  Сохранение состояния (переживает рестарт сервера)
    // =====================================================================================

    private File stateFile() {
        return new File(plugin.getDataFolder(), "data.yml");
    }

    private void saveState() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("last-trigger", lastTrigger);
        y.set("active", phase != Phase.IDLE);
        if (phase != Phase.IDLE && world != null) {
            y.set("phase", phase.name());
            y.set("world", world.getName());
            y.set("x", px);
            y.set("y", py);
            y.set("z", pz);
            y.set("spawned-at", spawnedAt);
            y.set("open-at", openAt);
            y.set("end-at", endAt);
            y.set("guards-total", guardsTotal);
        }
        try {
            y.save(stateFile());
        } catch (IOException e) {
            plugin.getLogger().warning("Не удалось сохранить data.yml: " + e.getMessage());
        }
    }

    private void loadState() {
        File f = stateFile();
        if (!f.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        lastTrigger = y.getString("last-trigger", "");
        if (!y.getBoolean("active")) return;

        World w = Bukkit.getWorld(y.getString("world", ""));
        if (w == null) {
            plugin.getLogger().warning("Мир активной арены не найден - состояние сброшено.");
            return;
        }
        world = w;
        px = y.getInt("x");
        py = y.getInt("y");
        pz = y.getInt("z");
        phase = Phase.valueOf(y.getString("phase", "LOCKED"));
        spawnedAt = y.getLong("spawned-at");
        openAt = y.getLong("open-at");
        endAt = y.getLong("end-at");
        guardsTotal = y.getInt("guards-total", cfg().getInt("guards.count", 8));
        recalc();
        forceLoad(true);

        long now = System.currentTimeMillis();
        if (phase == Phase.OPEN && now >= endAt) {
            removeArena(false);
            return;
        }
        if (phase == Phase.LOCKED && now >= openAt) {
            unlock();
        }
        // восстанавливаем стражей, голограммы и боссбар
        for (Entity e : w.getLivingEntities()) {
            if (isGuard(e)) guards.add(e.getUniqueId());
        }
        if (phase == Phase.OPEN && prizeInv == null) prizeInv = buildPrize();
        createBar();
        spawnHolos();
        plugin.getLogger().info("Арена восстановлена после рестарта: " + px + " " + py + " " + pz);
    }

    // =====================================================================================
    //  Утилиты
    // =====================================================================================

    public boolean protectBreak() {
        return cfg().getBoolean("protect.break", true);
    }

    public boolean protectExplosions() {
        return cfg().getBoolean("protect.explosions", true);
    }

    private Location safeAbove(World w, int x, int y, int z) {
        int yy = y;
        for (int i = 0; i < 40; i++) {
            Block feet = w.getBlockAt(x, yy, z);
            Block head = feet.getRelative(0, 1, 0);
            if (feet.isPassable() && head.isPassable() && !feet.isLiquid()) break;
            yy++;
        }
        return new Location(w, x + 0.5, yy, z + 0.5);
    }
}
