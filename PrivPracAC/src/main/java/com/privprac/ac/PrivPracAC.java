package com.privprac.ac;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public final class PrivPracAC extends JavaPlugin implements TabExecutor {

    private final Map<UUID, PlayerData> data = new ConcurrentHashMap<>();
    private final Set<UUID> alertsOff = ConcurrentHashMap.newKeySet();
    private ViolationManager violations;
    private CrystalChecks checks;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        violations = new ViolationManager(this);
        checks = new CrystalChecks(this, violations);
        Bukkit.getPluginManager().registerEvents(checks, this);

        var cmd = getCommand("privprac");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }

        Bukkit.getScheduler().runTaskTimer(this, () -> {
            violations.decay();
            checks.cleanup();
        }, 20L, 20L);

        getLogger().info("PrivPrac AC enabled - watching for Auto Crystal / Crystal Aura.");
    }

    PlayerData data(Player p) {
        return data.computeIfAbsent(p.getUniqueId(), k -> new PlayerData());
    }

    Map<UUID, PlayerData> all() {
        return data;
    }

    void forget(UUID id) {
        data.remove(id);
    }

    boolean alertsEnabled(Player p) {
        return !alertsOff.contains(p.getUniqueId());
    }

    // ------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        String pre = violations.prefix();
        if (a.length == 0) {
            s.sendMessage(pre + ChatColor.GRAY + "/privprac <reload | alerts | vl <player> | reset <player>>");
            return true;
        }
        switch (a[0].toLowerCase()) {
            case "reload" -> {
                reloadConfig();
                s.sendMessage(pre + ChatColor.GREEN + "Config reloaded.");
            }
            case "alerts" -> {
                if (!(s instanceof Player p)) {
                    s.sendMessage(pre + "Players only.");
                    return true;
                }
                if (alertsOff.remove(p.getUniqueId())) {
                    p.sendMessage(pre + ChatColor.GREEN + "Alerts enabled.");
                } else {
                    alertsOff.add(p.getUniqueId());
                    p.sendMessage(pre + ChatColor.RED + "Alerts disabled.");
                }
            }
            case "vl", "reset" -> {
                if (a.length < 2) {
                    s.sendMessage(pre + ChatColor.RED + "Usage: /privprac " + a[0] + " <player>");
                    return true;
                }
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t == null) {
                    s.sendMessage(pre + ChatColor.RED + "Player not online.");
                    return true;
                }
                if (a[0].equalsIgnoreCase("vl")) {
                    s.sendMessage(pre + ChatColor.WHITE + t.getName() + ChatColor.GRAY + " -> " + violations.summary(t.getUniqueId()));
                } else {
                    data(t).vl.clear();
                    s.sendMessage(pre + ChatColor.GREEN + "Cleared violations for " + t.getName() + ".");
                }
            }
            default -> s.sendMessage(pre + ChatColor.RED + "Unknown subcommand.");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        if (a.length == 1) {
            return List.of("reload", "alerts", "vl", "reset").stream()
                    .filter(x -> x.startsWith(a[0].toLowerCase())).collect(Collectors.toList());
        }
        if (a.length == 2 && (a[0].equalsIgnoreCase("vl") || a[0].equalsIgnoreCase("reset"))) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(n -> n.toLowerCase().startsWith(a[1].toLowerCase())).collect(Collectors.toList());
        }
        return List.of();
    }
}
