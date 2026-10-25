package net.intertime.bar;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class InterBar extends JavaPlugin {

    private BarManager manager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        manager = new BarManager(this);
        getServer().getPluginManager().registerEvents(manager, this);
        manager.start();
        getLogger().info("InterBar увімкнено!");
    }

    @Override
    public void onDisable() {
        if (manager != null) manager.shutdown();
    }

    private static void say(CommandSender s, String text) {
        s.sendMessage(Text.colorize(BarManager.PREFIX + text, 10000));
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (a.length > 0 && a[0].equalsIgnoreCase("reload")) {
            if (!s.hasPermission("intertime.bar.admin")) {
                say(s, "&cНемає прав.");
                return true;
            }
            reloadConfig();
            manager.reload();
            say(s, "&aКонфіг перезавантажено.");
            return true;
        }

        if (a.length > 0 && (a[0].equalsIgnoreCase("ether") || a[0].equalsIgnoreCase("ефіри")
                || a[0].equalsIgnoreCase("donate"))) {
            if (a.length == 1) {
                if (s instanceof Player) {
                    Player p = (Player) s;
                    BarManager.msg(p, "&7Ефіри: &d" + BarManager.fmt(manager.etherOf(p.getUniqueId())));
                }
                return true;
            }
            if (!s.hasPermission("intertime.bar.admin")) {
                say(s, "&cНемає прав.");
                return true;
            }
            if (a.length < 4) {
                say(s, "&7Використання: &f/bar ether <give|take|set> <гравець> <сума>");
                return true;
            }
            Player t = Bukkit.getPlayerExact(a[2]);
            if (t == null) {
                say(s, "&cГравця не знайдено.");
                return true;
            }
            long n;
            try {
                n = Long.parseLong(a[3]);
            } catch (NumberFormatException ex) {
                say(s, "&cСума має бути числом.");
                return true;
            }
            String op = a[1].toLowerCase();
            if (op.equals("give")) manager.addEther(t.getUniqueId(), n);
            else if (op.equals("take")) manager.addEther(t.getUniqueId(), -n);
            else if (op.equals("set")) manager.setEther(t.getUniqueId(), n);
            else {
                say(s, "&7Використання: &f/bar ether <give|take|set> <гравець> <сума>");
                return true;
            }
            say(s, "&7Ефіри &f" + t.getName() + "&7: &d" + BarManager.fmt(manager.etherOf(t.getUniqueId())));
            return true;
        }

        if (!(s instanceof Player)) {
            s.sendMessage("Тільки для гравців.");
            return true;
        }
        Player p = (Player) s;
        if (a.length >= 2 && a[0].equalsIgnoreCase("toggle")) {
            String key = a[1].toLowerCase();
            if (key.equals("sidebar") || key.equals("bossbar") || key.equals("tab")) {
                manager.toggle(p, key);
                BarManager.msg(p, "&7Панель &f" + key + "&7: "
                        + (manager.isOn(p, key) ? "&aувімкнено" : "&cвимкнено"));
                return true;
            }
        }
        manager.openMenu(p);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] a) {
        List<String> out = new ArrayList<String>();
        if (a.length == 1) {
            out.addAll(Arrays.asList("toggle", "ether", "reload"));
        } else if (a.length == 2 && a[0].equalsIgnoreCase("toggle")) {
            out.addAll(Arrays.asList("sidebar", "bossbar", "tab"));
        } else if (a.length == 2 && a[0].equalsIgnoreCase("ether")) {
            out.addAll(Arrays.asList("give", "take", "set"));
        } else if (a.length == 3 && a[0].equalsIgnoreCase("ether")) {
            for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        }
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase();
        List<String> res = new ArrayList<String>();
        for (String x : out) if (x.toLowerCase().startsWith(last)) res.add(x);
        return res;
    }
}
