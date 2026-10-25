package net.intertime.bar;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

public class BarManager implements Listener {

    static final String PREFIX = "<g:#e040fb:#7c4dff:b>\u026a\u0274\u1d1b\u1d07\u0280\u1d1b\u026a\u1d0d\u1d07</g> &8\u00bb ";
    private static final String[] KEYS = {"sidebar", "bossbar", "tab"};
    private static final int MAX_LINES = 15;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    // статистика (назви різняться між версіями)
    private static final Statistic ST_PLAY = stat("PLAY_ONE_MINUTE", "PLAY_TIME");
    private static final Statistic ST_KILLS = stat("PLAYER_KILLS");
    private static final Statistic ST_DEATHS = stat("DEATHS");
    private static final Statistic ST_MOBS = stat("MOB_KILLS");

    private static Method pingMethod;

    static {
        try {
            pingMethod = Player.class.getMethod("getPing");
        } catch (Throwable ignored) {
        }
    }

    static class Rank {
        final String id, perm, display, color;

        Rank(String id, String perm, String display, String color) {
            this.id = id;
            this.perm = perm;
            this.display = display;
            this.color = color;
        }
    }

    static class Msg {
        final String text;
        final BarColor color;
        final BarStyle style;

        Msg(String text, BarColor color, BarStyle style) {
            this.text = text;
            this.color = color;
            this.style = style;
        }
    }

    /** Що ми вже показали гравцю (щоб не слати зайві пакети). */
    static class State {
        String title;
        String boss;
        String tab;
        int count = -1;
        final String[] lines = new String[MAX_LINES];
    }

    static class Gui implements InventoryHolder {
        Inventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private final InterBar pl;
    private final Map<UUID, Scoreboard> boards = new HashMap<UUID, Scoreboard>();
    private final Map<UUID, BossBar> bars = new HashMap<UUID, BossBar>();
    private final Map<UUID, State> states = new HashMap<UUID, State>();
    private final Map<UUID, Integer> rankCache = new HashMap<UUID, Integer>();
    private final Map<String, Set<UUID>> off = new HashMap<String, Set<UUID>>();
    private final Map<UUID, Long> ether = new HashMap<UUID, Long>();

    private final File dataFile;
    private BukkitTask task;
    private long ticks;
    private boolean errorLogged;
    private Method papi;
    private VaultBridge vault;
    private boolean vaultTried;

    // конфіг
    private int updateTicks, switchSeconds, bossInterval;
    private boolean sidebarOn, bossOn, tabOn, sortRank;
    private String serverName, serverLink, titleRaw;
    private List<List<String>> pages = new ArrayList<List<String>>();
    private List<String> header, footer;
    private List<Msg> msgs = new ArrayList<Msg>();
    private List<Rank> ranks = new ArrayList<Rank>();

    public BarManager(InterBar pl) {
        this.pl = pl;
        this.dataFile = new File(pl.getDataFolder(), "data.yml");
        for (String k : KEYS) off.put(k, new HashSet<UUID>());
        loadData();
        load();
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            try {
                papi = Class.forName("me.clip.placeholderapi.PlaceholderAPI")
                        .getMethod("setPlaceholders", Player.class, String.class);
            } catch (Throwable ignored) {
            }
        }
    }

    private static Statistic stat(String... names) {
        for (String n : names) {
            try {
                return Statistic.valueOf(n);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static int stat(Player p, Statistic s) {
        try {
            return s == null ? 0 : p.getStatistic(s);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int ping(Player p) {
        try {
            if (pingMethod != null) return (Integer) pingMethod.invoke(p);
            Object h = p.getClass().getMethod("getHandle").invoke(p);
            return h.getClass().getField("ping").getInt(h);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static <E extends Enum<E>> E en(Class<E> c, String s, E def) {
        try {
            return Enum.valueOf(c, s.toUpperCase());
        } catch (Exception e) {
            return def;
        }
    }

    // ================================================================ конфіг / дані

    private void load() {
        FileConfiguration c = pl.getConfig();
        updateTicks = Math.max(2, c.getInt("update-ticks", 10));
        sidebarOn = c.getBoolean("sidebar.enabled", true);
        bossOn = c.getBoolean("bossbar.enabled", true);
        tabOn = c.getBoolean("tab.enabled", true);
        sortRank = c.getBoolean("tab.sort-by-rank", true);
        switchSeconds = Math.max(1, c.getInt("sidebar.switch-seconds", 6));
        bossInterval = Math.max(1, c.getInt("bossbar.interval", 10));
        serverName = c.getString("server-name", "InterTime");
        serverLink = c.getString("server-link", "MYSERVER.NET");
        titleRaw = c.getString("sidebar.title", "&dInterTime");
        header = c.getStringList("tab.header");
        footer = c.getStringList("tab.footer");

        pages = new ArrayList<List<String>>();
        for (int i = 1; i <= 10; i++) {
            List<String> l = c.getStringList("sidebar.page-" + i);
            if (l.isEmpty()) break;
            pages.add(l.size() > MAX_LINES ? new ArrayList<String>(l.subList(0, MAX_LINES)) : l);
        }
        if (pages.isEmpty()) pages.add(Arrays.asList("&7%player%"));

        msgs = new ArrayList<Msg>();
        for (Map<?, ?> m : c.getMapList("bossbar.messages")) {
            msgs.add(new Msg(String.valueOf(m.get("text")),
                    en(BarColor.class, String.valueOf(m.get("color")), BarColor.PURPLE),
                    en(BarStyle.class, String.valueOf(m.get("style")), BarStyle.SOLID)));
        }

        ranks = new ArrayList<Rank>();
        for (Map<?, ?> m : c.getMapList("ranks")) {
            ranks.add(new Rank(String.valueOf(m.get("id")),
                    m.get("permission") == null ? "" : String.valueOf(m.get("permission")),
                    m.get("display") == null ? "" : String.valueOf(m.get("display")),
                    m.get("color") == null ? "GRAY" : String.valueOf(m.get("color"))));
        }
        if (ranks.isEmpty()) ranks.add(new Rank("player", "", "&7Player", "GRAY"));
        if (ranks.size() > 100) ranks = new ArrayList<Rank>(ranks.subList(0, 100));
    }

    private void loadData() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        for (String section : new String[]{"ether", "donate"}) {
            ConfigurationSection ds = y.getConfigurationSection(section);
            if (ds == null) continue;
            for (String k : ds.getKeys(false)) {
                try {
                    ether.put(UUID.fromString(k), ds.getLong(k));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        for (String k : KEYS) {
            for (String s : y.getStringList(k + "-off")) {
                try {
                    off.get(k).add(UUID.fromString(s));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
    }

    private void saveData() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, Long> e : ether.entrySet()) y.set("ether." + e.getKey(), e.getValue());
        for (String k : KEYS) {
            List<String> l = new ArrayList<String>();
            for (UUID u : off.get(k)) l.add(u.toString());
            y.set(k + "-off", l);
        }
        try {
            pl.getDataFolder().mkdirs();
            y.save(dataFile);
        } catch (IOException ex) {
            pl.getLogger().warning("Не вдалось зберегти data.yml: " + ex.getMessage());
        }
    }

    // ================================================================ запуск / зупинка

    public void start() {
        if (task != null) task.cancel();
        task = Bukkit.getScheduler().runTaskTimer(pl, new Runnable() {
            @Override
            public void run() {
                tick();
            }
        }, 20L, updateTicks);
    }

    public void shutdown() {
        if (task != null) task.cancel();
        clearAll();
    }

    public void reload() {
        clearAll();
        load();
        start();
    }

    private void clearAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            BossBar b = bars.get(p.getUniqueId());
            if (b != null) b.removeAll();
            try {
                Compat.listHeader(p, "");
                Compat.listFooter(p, "");
                Compat.listName(p, null);
            } catch (Throwable ignored) {
            }
            if (boards.containsKey(p.getUniqueId())) {
                p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
            }
        }
        boards.clear();
        bars.clear();
        states.clear();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        boards.remove(id);
        BossBar b = bars.remove(id);
        if (b != null) b.removeAll();
        states.remove(id);
        for (Scoreboard sb : boards.values()) {
            Team t = sb.getEntryTeam(p.getName());
            if (t != null) t.removeEntry(p.getName());
        }
    }

    // ================================================================ головний цикл

    private void tick() {
        ticks++;
        rankCache.clear();
        for (Player p : Bukkit.getOnlinePlayers()) rankCache.put(p.getUniqueId(), rankOf(p));
        for (Player p : Bukkit.getOnlinePlayers()) {
            try {
                sidebar(p);
                bossbar(p);
                tab(p);
                teams(p);
            } catch (Throwable ex) {
                if (!errorLogged) {
                    errorLogged = true;
                    pl.getLogger().warning("Помилка оновлення для " + p.getName() + ": " + ex);
                    ex.printStackTrace();
                }
            }
        }
    }

    private int rankOf(Player p) {
        for (int i = 0; i < ranks.size(); i++) {
            Rank r = ranks.get(i);
            if (r.perm.isEmpty() || p.hasPermission(r.perm)) return i;
        }
        return ranks.size() - 1;
    }

    private Rank rank(Player p) {
        Integer i = rankCache.get(p.getUniqueId());
        return ranks.get(i == null ? ranks.size() - 1 : Math.min(i, ranks.size() - 1));
    }

    // ================================================================ плейсхолдери

    static String fmt(long n) {
        return String.format(Locale.US, "%,d", n).replace(',', ' ');
    }

    private long money(Player p) {
        if (!vaultTried) {
            vaultTried = true;
            vault = VaultBridge.create();
        }
        return vault == null ? 0 : vault.balance(p);
    }

    public long etherOf(UUID id) {
        Long v = ether.get(id);
        return v == null ? 0 : v;
    }

    public void setEther(UUID id, long amount) {
        ether.put(id, Math.max(0, amount));
        saveData();
    }

    public void addEther(UUID id, long amount) {
        setEther(id, etherOf(id) + amount);
    }

    private static String rep(String s, String key, Supplier<String> v) {
        return s.contains(key) ? s.replace(key, v.get()) : s;
    }

    private String ph(final Player p, String s) {
        if (s.indexOf('%') < 0) return s;
        s = s.replace("%server%", serverName).replace("%link%", serverLink);
        s = rep(s, "%player%", new Supplier<String>() {
            public String get() {
                return p.getName();
            }
        });
        s = rep(s, "%rank%", new Supplier<String>() {
            public String get() {
                return rank(p).display;
            }
        });
        s = rep(s, "%balance%", new Supplier<String>() {
            public String get() {
                return fmt(money(p));
            }
        });
        s = rep(s, "%ether%", new Supplier<String>() {
            public String get() {
                return fmt(etherOf(p.getUniqueId()));
            }
        });
        s = rep(s, "%hours%", new Supplier<String>() {
            public String get() {
                return String.valueOf(stat(p, ST_PLAY) / 72000L);
            }
        });
        s = rep(s, "%playtime%", new Supplier<String>() {
            public String get() {
                long mins = stat(p, ST_PLAY) / 1200L;
                return (mins / 60) + "\u0433 " + (mins % 60) + "\u0445\u0432";
            }
        });
        s = rep(s, "%ping%", new Supplier<String>() {
            public String get() {
                return String.valueOf(ping(p));
            }
        });
        s = rep(s, "%kills%", new Supplier<String>() {
            public String get() {
                return String.valueOf(stat(p, ST_KILLS));
            }
        });
        s = rep(s, "%deaths%", new Supplier<String>() {
            public String get() {
                return String.valueOf(stat(p, ST_DEATHS));
            }
        });
        s = rep(s, "%mobkills%", new Supplier<String>() {
            public String get() {
                return String.valueOf(stat(p, ST_MOBS));
            }
        });
        s = rep(s, "%kdr%", new Supplier<String>() {
            public String get() {
                double d = Math.max(1, stat(p, ST_DEATHS));
                return String.format(Locale.US, "%.2f", stat(p, ST_KILLS) / d);
            }
        });
        s = rep(s, "%online%", new Supplier<String>() {
            public String get() {
                return String.valueOf(Bukkit.getOnlinePlayers().size());
            }
        });
        s = rep(s, "%max%", new Supplier<String>() {
            public String get() {
                return String.valueOf(Bukkit.getMaxPlayers());
            }
        });
        s = rep(s, "%world%", new Supplier<String>() {
            public String get() {
                return p.getWorld().getName();
            }
        });
        s = rep(s, "%time%", new Supplier<String>() {
            public String get() {
                return LocalTime.now().format(TIME);
            }
        });
        s = rep(s, "%date%", new Supplier<String>() {
            public String get() {
                return LocalDate.now().format(DATE);
            }
        });
        s = rep(s, "%tps%", new Supplier<String>() {
            public String get() {
                try {
                    // getTPS() є лише в Paper/Spigot нових версій, тому викликаємо через рефлексію
                    Object spigot = Bukkit.getServer().getClass().getMethod("spigot").invoke(Bukkit.getServer());
                    double[] tps = (double[]) spigot.getClass().getMethod("getTPS").invoke(spigot);
                    return String.format(Locale.US, "%.1f", Math.min(20.0, tps[0]));
                } catch (Throwable t) {
                    return "20.0";
                }
            }
        });
        s = rep(s, "%health%", new Supplier<String>() {
            public String get() {
                return String.valueOf((int) Math.ceil(p.getHealth()));
            }
        });
        if (papi != null) {
            try {
                s = (String) papi.invoke(null, p, s);
            } catch (Throwable ignored) {
            }
        }
        return s;
    }

    // ================================================================ скорборд справа

    private static String entry(int i) {
        return "\u00a7" + Integer.toHexString(i) + "\u00a7r";
    }

    private Objective newObjective(Scoreboard sb) {
        try {
            return sb.registerNewObjective("ib", "dummy", "InterTime");
        } catch (Throwable t) {
            try {
                Class<?> crit = Class.forName("org.bukkit.scoreboard.Criteria");
                Object dummy = crit.getField("DUMMY").get(null);
                Method m = Scoreboard.class.getMethod("registerNewObjective", String.class, crit, String.class);
                return (Objective) m.invoke(sb, "ib", dummy, "InterTime");
            } catch (Throwable t2) {
                throw new IllegalStateException("Не вдалось створити скорборд", t2);
            }
        }
    }

    private Scoreboard board(Player p) {
        Scoreboard sb = boards.get(p.getUniqueId());
        if (sb == null) {
            sb = Bukkit.getScoreboardManager().getNewScoreboard();
            Objective o = newObjective(sb);
            // приховати червоні цифри праворуч (Paper 1.20.3+; на інших просто пропускається)
            try {
                Class<?> nf = Class.forName("io.papermc.paper.scoreboard.numbers.NumberFormat");
                Object blank = nf.getMethod("blank").invoke(null);
                Objective.class.getMethod("numberFormat", nf).invoke(o, blank);
            } catch (Throwable ignored) {
            }
            for (int i = 0; i < MAX_LINES; i++) {
                Team t = sb.registerNewTeam("l" + i);
                t.addEntry(entry(i));
            }
            for (int i = 0; i < ranks.size(); i++) {
                Team t = sb.registerNewTeam(String.format("r%02d", i));
                Compat.prefix(t, Text.colorize(ranks.get(i).display + " ", 60));
                try {
                    t.setColor(ChatColor.valueOf(ranks.get(i).color.toUpperCase()));
                } catch (Throwable ignored) {
                }
            }
            boards.put(p.getUniqueId(), sb);
            states.remove(p.getUniqueId());
        }
        if (p.getScoreboard() != sb) p.setScoreboard(sb);
        return sb;
    }

    private int pageIndex() {
        long seconds = ticks * updateTicks / 20L;
        return (int) ((seconds / switchSeconds) % pages.size());
    }

    private void setLine(Team t, String s) {
        if (t == null) return;
        if (s.length() <= 64) {
            Compat.prefix(t, s);
            Compat.suffix(t, "");
            return;
        }
        int i = Text.safeSplit(s, 64);
        String pre = s.substring(0, i);
        String suf = Text.lastColors(pre) + s.substring(i);
        if (suf.length() > 64) suf = suf.substring(0, 64);
        Compat.prefix(t, pre);
        Compat.suffix(t, suf);
    }

    private void sidebar(Player p) {
        Scoreboard sb = board(p);
        Objective o = sb.getObjective("ib");
        if (o == null) return;
        if (!sidebarOn || !isOn(p, "sidebar")) {
            sb.clearSlot(DisplaySlot.SIDEBAR);
            return;
        }
        if (o.getDisplaySlot() != DisplaySlot.SIDEBAR) o.setDisplaySlot(DisplaySlot.SIDEBAR);

        State st = states.get(p.getUniqueId());
        if (st == null) {
            st = new State();
            states.put(p.getUniqueId(), st);
        }
        String title = Text.colorize(ph(p, titleRaw), Compat.titleLimit());
        if (!title.equals(st.title)) {
            st.title = title;
            Compat.objectiveName(o, title);
        }

        List<String> page = pages.get(pageIndex());
        int n = page.size();
        for (int i = 0; i < n; i++) {
            String s = Text.colorize(ph(p, page.get(i)), 100);
            if (!s.equals(st.lines[i]) || st.count != n) {
                st.lines[i] = s;
                setLine(sb.getTeam("l" + i), s);
                o.getScore(entry(i)).setScore(n - i);
            }
        }
        for (int i = n; i < MAX_LINES; i++) {
            if (st.lines[i] != null) {
                sb.resetScores(entry(i));
                st.lines[i] = null;
            }
        }
        st.count = n;
    }

    // ================================================================ боссбар

    private void bossbar(Player p) {
        UUID id = p.getUniqueId();
        BossBar bar = bars.get(id);
        if (!bossOn || !isOn(p, "bossbar") || msgs.isEmpty()) {
            if (bar != null) {
                bar.removeAll();
                bars.remove(id);
            }
            return;
        }
        double elapsed = ticks * updateTicks / 20.0;
        int idx = (int) (elapsed / bossInterval) % msgs.size();
        float prog = (float) (1.0 - (elapsed % bossInterval) / bossInterval);
        Msg m = msgs.get(idx);
        State st = states.get(id);
        if (st == null) {
            st = new State();
            states.put(id, st);
        }
        if (bar == null) {
            bar = Bukkit.createBossBar("", BarColor.PURPLE, BarStyle.SOLID);
            bar.addPlayer(p);
            bars.put(id, bar);
            st.boss = null;
        }
        String title = Text.colorize(ph(p, m.text), 1000);
        if (!title.equals(st.boss)) {
            st.boss = title;
            Compat.bossTitle(bar, title);
        }
        bar.setColor(m.color);
        bar.setStyle(m.style);
        bar.setProgress(Math.max(0.0, Math.min(1.0, prog)));
    }

    // ================================================================ TAB

    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(l.get(i));
        }
        return sb.toString();
    }

    private void tab(Player p) {
        UUID id = p.getUniqueId();
        State st = states.get(id);
        if (st == null) {
            st = new State();
            states.put(id, st);
        }
        if (!tabOn || !isOn(p, "tab")) {
            if (!"off".equals(st.tab)) {
                st.tab = "off";
                Compat.listHeader(p, "");
                Compat.listFooter(p, "");
                Compat.listName(p, null);
            }
            return;
        }
        Rank r = rank(p);
        String h = Text.colorize(ph(p, join(header)), 5000);
        String f = Text.colorize(ph(p, join(footer)), 5000);
        String name = Text.colorize(r.display + " ", 200);
        ChatColor col = ChatColor.WHITE;
        try {
            col = ChatColor.valueOf(r.color.toUpperCase());
        } catch (Throwable ignored) {
        }
        String key = name + "\u0000" + h + "\u0000" + f;
        if (key.equals(st.tab)) return;
        st.tab = key;
        Compat.listName(p, name + col + p.getName());
        Compat.listHeader(p, h);
        Compat.listFooter(p, f);
    }

    /** Команди (teams) на скорборді глядача: сортування в TAB і префікс над головою. */
    private void teams(Player viewer) {
        if (!tabOn || !sortRank) return;
        Scoreboard sb = boards.get(viewer.getUniqueId());
        if (sb == null) return;
        for (Player q : Bukkit.getOnlinePlayers()) {
            Integer idx = rankCache.get(q.getUniqueId());
            if (idx == null) continue;
            Team t = sb.getTeam(String.format("r%02d", idx));
            if (t == null || t.hasEntry(q.getName())) continue;
            Team old = sb.getEntryTeam(q.getName());
            if (old != null) old.removeEntry(q.getName());
            t.addEntry(q.getName());
        }
    }

    // ================================================================ перемикачі та меню

    public boolean isOn(Player p, String key) {
        return !off.get(key).contains(p.getUniqueId());
    }

    public void toggle(Player p, String key) {
        Set<UUID> set = off.get(key);
        if (!set.remove(p.getUniqueId())) set.add(p.getUniqueId());
        saveData();
    }

    static void msg(Player p, String s) {
        p.sendMessage(Text.colorize(PREFIX + s, 10000));
    }

    private static ItemStack item(Material m, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta im = it.getItemMeta();
        Compat.itemName(im, Text.colorize(name, 200));
        if (lore.length > 0) {
            List<String> l = new ArrayList<String>();
            for (String s : lore) l.add(Text.colorize(s, 200));
            Compat.itemLore(im, l);
        }
        it.setItemMeta(im);
        return it;
    }

    public void openMenu(Player p) {
        Gui g = new Gui();
        g.inv = Compat.inventory(g, 27, Text.colorize("<g:#e040fb:#7c4dff:b>InterBar</g> &8\u2022 &f\u041f\u0430\u043d\u0435\u043b\u0456", 120));
        draw(g.inv, p);
        p.openInventory(g.inv);
    }

    private void draw(Inventory inv, Player p) {
        ItemStack edge = item(Material.PURPLE_STAINED_GLASS_PANE, " ");
        ItemStack dark = item(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 27; i++) inv.setItem(i, (i < 9 || i >= 18) ? edge : dark);
        String[] names = {"\u0421\u043a\u043e\u0440\u0431\u043e\u0440\u0434 \u0441\u043f\u0440\u0430\u0432\u0430",
                "\u0411\u043e\u0441\u0441\u0431\u0430\u0440 \u0437\u0432\u0435\u0440\u0445\u0443",
                "TAB (\u0441\u043f\u0438\u0441\u043e\u043a \u0433\u0440\u0430\u0432\u0446\u0456\u0432)"};
        Material[] icons = {Material.OAK_SIGN, Material.DRAGON_HEAD, Material.NAME_TAG};
        int[] slots = {11, 13, 15};
        for (int i = 0; i < 3; i++) {
            boolean on = isOn(p, KEYS[i]);
            inv.setItem(slots[i], item(icons[i], (on ? "&a\u2714 " : "&c\u2718 ") + names[i],
                    "&7\u0421\u0442\u0430\u043d: " + (on ? "&a\u0443\u0432\u0456\u043c\u043a\u043d\u0435\u043d\u043e" : "&c\u0432\u0438\u043c\u043a\u043d\u0435\u043d\u043e"),
                    "&e\u041d\u0430\u0442\u0438\u0441\u043d\u0438 \u0449\u043e\u0431 " + (on ? "\u0432\u0438\u043c\u043a\u043d\u0443\u0442\u0438" : "\u0443\u0432\u0456\u043c\u043a\u043d\u0443\u0442\u0438")));
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Gui)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player)) return;
        Player p = (Player) e.getWhoClicked();
        if (e.getClickedInventory() != e.getInventory()) return;
        int s = e.getSlot();
        int idx = s == 11 ? 0 : s == 13 ? 1 : s == 15 ? 2 : -1;
        if (idx < 0) return;
        toggle(p, KEYS[idx]);
        draw(e.getInventory(), p);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Gui) e.setCancelled(true);
    }
}
