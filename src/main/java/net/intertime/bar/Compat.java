package net.intertime.bar;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.boss.BossBar;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Team;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Сумісність 1.16.5 - найновіші версії.
 * Спочатку викликається звичайний (рядковий) метод Bukkit. Якщо в новій версії його прибрали
 * (NoSuchMethodError), підключається Adventure (Component) через рефлексію.
 */
final class Compat {

    private static Object serializer;
    private static Method deserialize;
    private static boolean tried;

    private Compat() {
    }

    /** Ліміт довжини назви скорборду: 32 символи до 1.17.1, далі практично без ліміту. */
    static int titleLimit() {
        try {
            String v = Bukkit.getBukkitVersion().split("-")[0];
            String[] p = v.split("\\.");
            int major = Integer.parseInt(p[0]);
            int minor = p.length > 1 ? Integer.parseInt(p[1]) : 0;
            if (major == 1 && minor <= 17) return 32;
        } catch (Throwable ignored) {
        }
        return 120;
    }

    private static synchronized Object comp(String legacy) {
        if (legacy == null) return null;
        if (!tried) {
            tried = true;
            try {
                Class<?> c = Class.forName("net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer");
                Class<?> bc = Class.forName("net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer$Builder");
                Object b = c.getMethod("builder").invoke(null);
                b = bc.getMethod("character", char.class).invoke(b, '\u00a7');
                b = bc.getMethod("hexColors").invoke(b);
                b = bc.getMethod("useUnusualXRepeatedCharacterHexFormat").invoke(b);
                serializer = bc.getMethod("build").invoke(b);
                for (Method m : c.getMethods()) {
                    if (m.getName().equals("deserialize") && m.getParameterTypes().length == 1) {
                        deserialize = m;
                        break;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        try {
            return deserialize == null ? null : deserialize.invoke(serializer, legacy);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Викликає метод з Component-аргументом (Paper) на інтерфейсі iface. */
    private static void viaComponent(Class<?> iface, Object target, String name, String legacy) {
        try {
            Object c = comp(legacy);
            for (Method m : iface.getMethods()) {
                if (!m.getName().equals(name) || m.getParameterTypes().length != 1) continue;
                if (c == null && legacy != null) continue;
                if (c != null && !m.getParameterTypes()[0].isInstance(c)) continue;
                m.invoke(target, c);
                return;
            }
        } catch (Throwable ignored) {
        }
    }

    static void prefix(Team t, String s) {
        try {
            t.setPrefix(s);
        } catch (LinkageError | UnsupportedOperationException e) {
            viaComponent(Team.class, t, "prefix", s);
        }
    }

    static void suffix(Team t, String s) {
        try {
            t.setSuffix(s);
        } catch (LinkageError | UnsupportedOperationException e) {
            viaComponent(Team.class, t, "suffix", s);
        }
    }

    static void objectiveName(Objective o, String s) {
        try {
            o.setDisplayName(s);
        } catch (LinkageError | UnsupportedOperationException e) {
            viaComponent(Objective.class, o, "displayName", s);
        }
    }

    static void bossTitle(BossBar b, String s) {
        try {
            b.setTitle(s);
        } catch (LinkageError | UnsupportedOperationException e) {
            viaComponent(BossBar.class, b, "title", s);
        }
    }

    static void listName(Player p, String s) {
        try {
            p.setPlayerListName(s);
        } catch (LinkageError | UnsupportedOperationException e) {
            viaComponent(Player.class, p, "playerListName", s);
        }
    }

    static void listHeader(Player p, String s) {
        try {
            p.setPlayerListHeader(s);
        } catch (LinkageError | UnsupportedOperationException e) {
            viaComponent(Player.class, p, "sendPlayerListHeader", s);
        }
    }

    static void listFooter(Player p, String s) {
        try {
            p.setPlayerListFooter(s);
        } catch (LinkageError | UnsupportedOperationException e) {
            viaComponent(Player.class, p, "sendPlayerListFooter", s);
        }
    }

    static void itemName(ItemMeta m, String s) {
        try {
            m.setDisplayName(s);
        } catch (LinkageError | UnsupportedOperationException e) {
            viaComponent(ItemMeta.class, m, "displayName", s);
        }
    }

    static void itemLore(ItemMeta m, List<String> lore) {
        try {
            m.setLore(lore);
        } catch (LinkageError | UnsupportedOperationException e) {
            try {
                List<Object> list = new ArrayList<Object>();
                for (String s : lore) list.add(comp(s));
                for (Method me : ItemMeta.class.getMethods()) {
                    if (me.getName().equals("lore") && me.getParameterTypes().length == 1) {
                        me.invoke(m, list);
                        return;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    static Inventory inventory(InventoryHolder h, int size, String title) {
        try {
            return Bukkit.createInventory(h, size, title);
        } catch (LinkageError | UnsupportedOperationException e) {
            try {
                Object c = comp(title);
                for (Method m : Bukkit.class.getMethods()) {
                    Class<?>[] pt = m.getParameterTypes();
                    if (m.getName().equals("createInventory") && pt.length == 3 && pt[1] == int.class
                            && c != null && pt[2].isInstance(c)) {
                        return (Inventory) m.invoke(null, h, size, c);
                    }
                }
            } catch (Throwable ignored) {
            }
            return Bukkit.createInventory(h, size);
        }
    }
}
