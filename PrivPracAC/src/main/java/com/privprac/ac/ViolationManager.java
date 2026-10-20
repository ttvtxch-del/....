package com.privprac.ac;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

final class ViolationManager {

    private final PrivPracAC plugin;

    ViolationManager(PrivPracAC plugin) {
        this.plugin = plugin;
    }

    String prefix() {
        return ChatColor.translateAlternateColorCodes('&', plugin.getConfig().getString("prefix", "&8[&dPrivPrac AC&8] "));
    }

    /**
     * Adds violation level for a check, alerts staff and runs punishment when thresholds are reached.
     *
     * @param check  display name, e.g. "CrystalAura (Angle)"
     * @param weight key under "weights" in config
     */
    void flag(Player p, String check, String weight, String detail) {
        FileConfiguration cfg = plugin.getConfig();
        PlayerData d = plugin.data(p);

        double add = cfg.getDouble("weights." + weight, 1.0);
        double vl = d.vl.merge(check, add, Double::sum);

        long now = System.currentTimeMillis();
        if (vl >= cfg.getDouble("alert-vl", 2.0) && now - d.lastAlert.getOrDefault(check, 0L) >= 1000L) {
            d.lastAlert.put(check, now);
            String msg = prefix() + ChatColor.WHITE + p.getName() + ChatColor.GRAY + " failed " + ChatColor.LIGHT_PURPLE + check
                    + ChatColor.DARK_GRAY + " (" + ChatColor.GRAY + detail + ChatColor.DARK_GRAY + ") "
                    + ChatColor.GRAY + "VL " + ChatColor.WHITE + String.format("%.1f", vl)
                    + ChatColor.DARK_GRAY + " | " + ChatColor.GRAY + "ping " + ChatColor.WHITE + p.getPing() + "ms";
            for (Player staff : Bukkit.getOnlinePlayers()) {
                if (staff.hasPermission("privprac.alerts") && plugin.alertsEnabled(staff)) {
                    staff.sendMessage(msg);
                }
            }
            plugin.getLogger().info(ChatColor.stripColor(msg));
        }

        if (cfg.getBoolean("punishment.enabled", false) && vl >= cfg.getDouble("punishment.vl", 15.0)) {
            d.vl.clear();
            for (String cmd : cfg.getStringList("punishment.commands")) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                        cmd.replace("%player%", p.getName()).replace("%check%", check));
            }
        }
    }

    /** Called once per second. */
    void decay() {
        double perSecond = plugin.getConfig().getDouble("decay-per-minute", 6.0) / 60.0;
        for (PlayerData d : plugin.all().values()) {
            Iterator<Map.Entry<String, Double>> it = d.vl.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Double> en = it.next();
                double v = en.getValue() - perSecond;
                if (v <= 0) it.remove();
                else en.setValue(v);
            }
        }
    }

    String summary(UUID id) {
        PlayerData d = plugin.all().get(id);
        if (d == null || d.vl.isEmpty()) return ChatColor.GRAY + "No violations.";
        StringBuilder sb = new StringBuilder();
        d.vl.forEach((k, v) -> sb.append(ChatColor.LIGHT_PURPLE).append(k).append(ChatColor.GRAY).append(": ")
                .append(ChatColor.WHITE).append(String.format("%.1f", v)).append("  "));
        return sb.toString();
    }
}
