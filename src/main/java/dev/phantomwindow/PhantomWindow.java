package dev.phantomwindow;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;

public class PhantomWindow extends JavaPlugin implements Listener, TabExecutor {

    private final Map<UUID, Double> gauge = new HashMap<>();
    private final Map<UUID, Long> lastEvent = new HashMap<>();
    private final Set<UUID> active = new HashSet<>();
    private final Random rnd = new Random();

    private List<Map.Entry<BlockData, Integer>> stoneTable = new ArrayList<>();
    private int stoneTotal = 0;
    private int taskId = -1;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand cmd = getCommand("distortion");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        load();
    }

    @Override
    public void onDisable() {
        for (UUID id : new ArrayList<>(active)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) resetClientState(p);
        }
        active.clear();
    }

    private void load() {
        reloadConfig();
        if (taskId != -1) Bukkit.getScheduler().cancelTask(taskId);

        stoneTable.clear();
        stoneTotal = 0;
        ConfigurationSection w = getConfig().getConfigurationSection("stone-weights");
        if (w != null) {
            addStone(w, "blackstone", Material.BLACKSTONE);
            addStone(w, "magma", Material.MAGMA_BLOCK);
            addStone(w, "sculk", Material.SCULK);
            addStone(w, "crying-obsidian", Material.CRYING_OBSIDIAN);
            addStone(w, "reinforced-deepslate", Material.REINFORCED_DEEPSLATE);
            addStone(w, "end-portal-frame", Material.END_PORTAL_FRAME);
        }
        if (stoneTable.isEmpty()) {
            stoneTable.add(Map.entry(Material.BLACKSTONE.createBlockData(), 1));
            stoneTotal = 1;
        }

        long period = Math.max(1, getConfig().getInt("tick-seconds", 1)) * 20L;
        taskId = Bukkit.getScheduler().runTaskTimer(this, this::tickAll, period, period).getTaskId();
    }

    private void addStone(ConfigurationSection s, String key, Material m) {
        int weight = s.getInt(key, 0);
        if (weight <= 0) return;
        stoneTable.add(Map.entry(m.createBlockData(), weight));
        stoneTotal += weight;
    }

    private boolean worldEnabled(World w) {
        List<String> list = getConfig().getStringList("enabled-worlds");
        if (list.isEmpty()) return w.getEnvironment() == World.Environment.NORMAL;
        return list.contains(w.getName());
    }

    private void tickAll() {
        for (Player p : Bukkit.getOnlinePlayers()) tick(p);
    }

    private void tick(Player p) {
        if (p.hasPermission("phantomwindow.immune")) return;
        GameMode gm = p.getGameMode();
        if (gm == GameMode.SPECTATOR || gm == GameMode.CREATIVE) return;
        if (!worldEnabled(p.getWorld())) return;

        UUID id = p.getUniqueId();
        ConfigurationSection g = getConfig().getConfigurationSection("gauge");
        if (g == null) return;

        double v = gauge.getOrDefault(id, 0.0);

        Location loc = p.getLocation();
        boolean deep = loc.getY() < g.getDouble("underground-y", 40);
        boolean dark = loc.getBlock().getLightLevel() < g.getInt("dark-light-level", 4);

        if (deep) v += g.getDouble("gain-underground", 2.0);
        if (dark) v += g.getDouble("gain-dark", 3.0);
        if (!deep && !dark) v -= g.getDouble("decay", 4.0);
        v = Math.max(0, Math.min(100, v));

        double threshold = g.getDouble("trigger-threshold", 60);
        if (v > threshold && !active.contains(id) && cooldownOver(id, g)) {
            double chance = (v - threshold) / (100.0 - threshold) * g.getDouble("max-chance", 0.5);
            if (rnd.nextDouble() < chance) {
                trigger(p);
                v = Math.max(0, v - g.getDouble("discharge", 40));
            }
        }
        gauge.put(id, v);
    }

    private boolean cooldownOver(UUID id, ConfigurationSection g) {
        long last = lastEvent.getOrDefault(id, 0L);
        return System.currentTimeMillis() - last >= g.getInt("cooldown-seconds", 30) * 1000L;
    }

    private void trigger(Player p) {
        UUID id = p.getUniqueId();
        if (!active.add(id)) return;
        lastEvent.put(id, System.currentTimeMillis());

        ConfigurationSection e = getConfig().getConfigurationSection("event");
        if (e == null) { active.remove(id); return; }

        World world = p.getWorld();
        Location c = p.getLocation();
        int r = Math.max(1, e.getInt("radius", 10));
        int cx = c.getBlockX(), cy = c.getBlockY(), cz = c.getBlockZ();
        int minY = world.getMinHeight(), maxY = world.getMaxHeight() - 1;

        Map<Location, BlockData> fake = new HashMap<>();
        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= r; y++) {
                int by = cy + y;
                if (by < minY || by > maxY) continue;
                for (int z = -r; z <= r; z++) {
                    Block b = world.getBlockAt(cx + x, by, cz + z);
                    Material m = b.getType();
                    if (m.isAir()) continue;
                    BlockData repl = replacement(m);
                    if (repl != null) fake.put(b.getLocation(), repl);
                }
            }
        }
        if (!fake.isEmpty()) p.sendMultiBlockChange(fake);

        p.setPlayerTime(e.getLong("fake-time", 13000), false);
        if (e.getBoolean("rain-sky", true)) p.setPlayerWeather(WeatherType.DOWNFALL);
        if (e.getBoolean("red-vignette", true)) {
            WorldBorder wb = Bukkit.createWorldBorder();
            wb.setCenter(c);
            wb.setSize(200_000);
            wb.setWarningDistance(1_000_000);
            wb.setWarningTime(0);
            p.setWorldBorder(wb);
        }
        int dur = Math.max(10, e.getInt("duration-ticks", 50));
        if (e.getBoolean("darkness-effect", true)) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, dur, 0, true, false, false));
        }
        if (e.getBoolean("sounds", true)) {
            p.playSound(c, Sound.AMBIENT_CAVE, 1f, 0.5f);
            p.playSound(c, Sound.ENTITY_WARDEN_NEARBY_CLOSE, 0.8f, 0.6f);
        }
        if (e.getBoolean("particles", true)) {
            p.spawnParticle(Particle.SCULK_SOUL, c.clone().add(0, 1, 0), 40, 2, 1, 2, 0.02);
        }

        Bukkit.getScheduler().runTaskLater(this, () -> endEvent(p, world, new ArrayList<>(fake.keySet())), dur);
    }

    private void endEvent(Player p, World world, List<Location> locs) {
        active.remove(p.getUniqueId());
        if (!p.isOnline()) return;
        resetClientState(p);

        if (p.getWorld().equals(world)) {
            Map<Location, BlockData> real = new HashMap<>();
            for (Location l : locs) real.put(l, l.getBlock().getBlockData());
            if (!real.isEmpty()) p.sendMultiBlockChange(real);
        }
    }

    private void resetClientState(Player p) {
        p.resetPlayerTime();
        p.resetPlayerWeather();
        p.setWorldBorder(null);
    }

    private BlockData replacement(Material m) {
        String n = m.name();

        if (n.endsWith("_LOG") || n.endsWith("_WOOD") || n.endsWith("_STEM")) {
            return Material.BLACKSTONE.createBlockData();
        }
        if (n.endsWith("_LEAVES")) return Material.AIR.createBlockData();
        if (n.contains("GRASS") && m != Material.GRASS_BLOCK) {
            return Material.DEAD_BUSH.createBlockData();
        }
        if (m == Material.GRASS_BLOCK || m == Material.DIRT || m == Material.PODZOL
                || m == Material.COARSE_DIRT || m == Material.MYCELIUM) {
            return rnd.nextInt(4) == 0 ? Material.SOUL_SOIL.createBlockData() : Material.BASALT.createBlockData();
        }
        if (m == Material.SAND || m == Material.GRAVEL) return Material.SOUL_SAND.createBlockData();

        switch (m) {
            case STONE, DEEPSLATE, COBBLESTONE, ANDESITE, DIORITE, GRANITE, TUFF, MOSSY_COBBLESTONE,
                 NETHERRACK, SANDSTONE, CALCITE, SMOOTH_BASALT -> {
                return pickStone();
            }
            default -> {
                return null;
            }
        }
    }

    private BlockData pickStone() {
        int roll = rnd.nextInt(stoneTotal);
        for (Map.Entry<BlockData, Integer> en : stoneTable) {
            roll -= en.getValue();
            if (roll < 0) return en.getKey();
        }
        return stoneTable.get(0).getKey();
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (shouldBlock(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (shouldBlock(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getClickedBlock() != null && shouldBlock(e.getPlayer())) e.setCancelled(true);
    }

    private boolean shouldBlock(Player p) {
        return active.contains(p.getUniqueId()) && getConfig().getBoolean("event.block-interaction", true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        active.remove(e.getPlayer().getUniqueId());
        gauge.remove(e.getPlayer().getUniqueId());
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (!s.hasPermission("phantomwindow.admin")) {
            s.sendMessage(getConfig().getString("messages.no-permission", "No permission."));
            return true;
        }
        if (a.length == 0) {
            s.sendMessage(getConfig().getString("messages.usage", "/distortion <get|set|add|trigger|reload>"));
            return true;
        }
        String sub = a[0].toLowerCase(Locale.ROOT);
        if (sub.equals("reload")) {
            load();
            s.sendMessage("§aPhantomWindow config reloaded.");
            return true;
        }

        Player target = a.length > 1 ? Bukkit.getPlayerExact(a[1]) : (s instanceof Player pl ? pl : null);
        if (target == null) {
            s.sendMessage("§cPlayer not found.");
            return true;
        }
        UUID id = target.getUniqueId();

        switch (sub) {
            case "get" -> s.sendMessage("§e" + target.getName() + ": §f"
                    + String.format("%.1f", gauge.getOrDefault(id, 0.0)) + " / 100");
            case "set", "add" -> {
                if (a.length < 3) { s.sendMessage("§c/distortion " + sub + " <player> <value>"); return true; }
                try {
                    double val = Double.parseDouble(a[2]);
                    double cur = gauge.getOrDefault(id, 0.0);
                    double res = sub.equals("set") ? val : cur + val;
                    gauge.put(id, Math.max(0, Math.min(100, res)));
                    s.sendMessage("§aGauge of " + target.getName() + ": " + gauge.get(id));
                } catch (NumberFormatException ex) {
                    s.sendMessage("§cNot a number.");
                }
            }
            case "trigger" -> {
                if (active.contains(id)) { s.sendMessage("§cAlready active."); return true; }
                trigger(target);
                s.sendMessage("§aTriggered for " + target.getName());
            }
            default -> s.sendMessage(getConfig().getString("messages.usage", "Unknown subcommand."));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (a.length == 1) {
            return filter(List.of("get", "set", "add", "trigger", "reload"), a[0]);
        }
        if (a.length == 2 && !a[0].equalsIgnoreCase("reload")) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
            return filter(names, a[1]);
        }
        return List.of();
    }

    private List<String> filter(List<String> src, String prefix) {
        List<String> out = new ArrayList<>();
        for (String x : src) if (x.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) out.add(x);
        return out;
    }
}
