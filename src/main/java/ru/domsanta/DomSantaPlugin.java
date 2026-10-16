package ru.domsanta;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public class DomSantaPlugin extends JavaPlugin {

    private SantaEvent active;
    private long nextStartMillis;
    private final Set<Integer> alertsSent = new HashSet<>();
    private BukkitTask mainTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        DomSantaCommand cmd = new DomSantaCommand(this);
        getCommand("domsanta").setExecutor(cmd);
        getCommand("domsanta").setTabCompleter(cmd);
        getServer().getPluginManager().registerEvents(new EventListener(this), this);
        scheduleNext();
        mainTask = getServer().getScheduler().runTaskTimer(this, this::tick, 20L, 20L);
    }

    @Override
    public void onDisable() {
        if (mainTask != null) mainTask.cancel();
        if (active != null) {
            active.end(false);
            active = null;
        }
    }

    // ---------------- основной цикл ----------------

    private void tick() {
        if (active != null) {
            active.tick();
            return;
        }
        if (!getConfig().getBoolean("auto-start.enabled", true)) return;

        long left = (nextStartMillis - System.currentTimeMillis()) / 1000L;
        for (int m : getConfig().getIntegerList("auto-start.alerts-minutes")) {
            if (left > 0 && left <= m * 60L && alertsSent.add(m)) {
                broadcast(list("alert", "time", alertText(m)));
            }
        }
        if (left <= 0) {
            int min = getConfig().getInt("auto-start.min-online-players", 0);
            int chance = getConfig().getInt("auto-start.spawn-chance", 50);
            if (Bukkit.getOnlinePlayers().size() < min || ThreadLocalRandom.current().nextInt(100) >= chance) {
                scheduleNext();
                return;
            }
            String err = startEvent();
            if (err != null) {
                getLogger().warning("Автозапуск не удался: " + err);
                nextStartMillis = System.currentTimeMillis() + 300_000L;
                alertsSent.clear();
                for (int m : getConfig().getIntegerList("auto-start.alerts-minutes")) alertsSent.add(m);
            }
        }
    }

    private String alertText(int m) {
        if (m >= 60 && m % 60 == 0) return (m / 60) + " ч.";
        return m + " мин.";
    }

    public void scheduleNext() {
        int minutes = getConfig().getInt("auto-start.interval-minutes", 180);
        nextStartMillis = System.currentTimeMillis() + minutes * 60_000L;
        alertsSent.clear();
        for (int m : getConfig().getIntegerList("auto-start.alerts-minutes")) {
            if (m >= minutes) alertsSent.add(m);
        }
    }

    public long secondsToNext() {
        return Math.max(0, (nextStartMillis - System.currentTimeMillis()) / 1000L);
    }

    // ---------------- управление ивентом ----------------

    /** @return null если успешно, иначе ключ сообщения об ошибке */
    public String startEvent() {
        if (active != null) return "already-running";
        World w = resolveWorld();
        if (w == null) return "no-world";
        StructureBuilder sb = StructureBuilder.find(this, w);
        if (sb == null) return "no-location";
        SantaEvent ev = new SantaEvent(this, sb);
        active = ev;
        try {
            ev.start();
        } catch (Exception ex) {
            getLogger().severe("Ошибка запуска ивента: " + ex);
            ex.printStackTrace();
            ev.end(false);
            active = null;
            scheduleNext();
            return "error";
        }
        announceStart(ev);
        return null;
    }

    private void announceStart(SantaEvent ev) {
        boolean coords = getConfig().getBoolean("players-see-coordinates", true);
        Location l = ev.getChestLocation();
        String[] kv = concat(coordKv(l),
                "open", fmt(getConfig().getInt("timers.open-after-seconds", 300)),
                "disappear", fmt(getConfig().getInt("timers.disappear-after-open-seconds", 1200)),
                "guards", String.valueOf(ev.getGuardCount()));
        broadcast(list(coords ? "start" : "start-nocoords", kv));
    }

    public boolean stopEvent() {
        if (active == null) return false;
        active.end(true);
        return true;
    }

    public void eventEnded(SantaEvent ev) {
        if (active == ev) {
            active = null;
            scheduleNext();
        }
    }

    public SantaEvent getActive() {
        return active;
    }

    public World resolveWorld() {
        World w = Bukkit.getWorld(getConfig().getString("world", "world"));
        if (w == null) {
            for (World x : Bukkit.getWorlds()) {
                if (x.getEnvironment() == World.Environment.NORMAL) return x;
            }
        }
        return w;
    }

    // ---------------- утилиты ----------------

    public String[] coordKv(Location l) {
        boolean show = getConfig().getBoolean("players-see-coordinates", true);
        String x = show ? String.valueOf(l.getBlockX()) : "???";
        String y = show ? String.valueOf(l.getBlockY()) : "???";
        String z = show ? String.valueOf(l.getBlockZ()) : "???";
        return new String[]{"x", x, "y", y, "z", z,
                "coords", show ? x + " " + y + " " + z : "скрыты",
                "world", l.getWorld().getName()};
    }

    public static String[] concat(String[] a, String... b) {
        String[] out = new String[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    public void broadcast(List<String> lines) {
        for (String s : lines) Bukkit.broadcastMessage(s);
    }

    public String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s);
    }

    public Component comp(String s) {
        return LegacyComponentSerializer.legacySection().deserialize(color(s));
    }

    public String replace(String s, String... kv) {
        for (int i = 0; i + 1 < kv.length; i += 2) s = s.replace("{" + kv[i] + "}", kv[i + 1]);
        return s;
    }

    public String raw(String key, String... kv) {
        return color(replace(getConfig().getString("messages." + key, key), kv));
    }

    public String msg(String key, String... kv) {
        return color(getConfig().getString("messages.prefix", "")) + raw(key, kv);
    }

    public List<String> list(String key, String... kv) {
        List<String> out = new ArrayList<>();
        for (String s : getConfig().getStringList("messages." + key)) out.add(color(replace(s, kv)));
        return out;
    }

    public String fmt(long sec) {
        long h = sec / 3600, m = (sec % 3600) / 60, s = sec % 60;
        if (h > 0) return String.format("%d:%02d:%02d", h, m, s);
        return String.format("%02d:%02d", m, s);
    }
}
