package com.privprac.ac;

import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

final class CrystalChecks implements Listener {

    private static final long MS = 1_000_000L;

    private final PrivPracAC plugin;
    private final ViolationManager vm;

    /** crystal entity UUID -> System.nanoTime() when it spawned. */
    private final Map<UUID, Long> spawnTimes = new HashMap<>();

    CrystalChecks(PrivPracAC plugin, ViolationManager vm) {
        this.plugin = plugin;
        this.vm = vm;
    }

    void cleanup() {
        long cutoff = System.nanoTime() - 30_000L * MS;
        Iterator<Map.Entry<UUID, Long>> it = spawnTimes.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue() < cutoff) it.remove();
        }
    }

    private boolean exempt(Player p) {
        return p.hasPermission("privprac.bypass") || p.getGameMode() == GameMode.SPECTATOR;
    }

    // ------------------------------------------------------------ tracking

    @EventHandler
    public void onSpawn(EntitySpawnEvent e) {
        if (e.getEntity() instanceof EnderCrystal) {
            spawnTimes.put(e.getEntity().getUniqueId(), System.nanoTime());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        plugin.forget(e.getPlayer().getUniqueId());
    }

    /** Tracks big, fast rotation changes (aura "snaps" to its target). */
    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location f = e.getFrom();
        Location t = e.getTo();
        if (t == null) return;
        float dy = Math.abs(wrap(t.getYaw() - f.getYaw()));
        float dp = Math.abs(t.getPitch() - f.getPitch());
        if (dy + dp < 1f) return;

        PlayerData d = plugin.data(e.getPlayer());
        long now = System.nanoTime();
        float delta = (float) Math.hypot(dy, dp);
        d.snapDelta = (now - d.snapNanos > 75 * MS) ? delta : Math.max(d.snapDelta, delta);
        d.snapNanos = now;
    }

    // ------------------------------------------------------------ placing

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlace(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = e.getItem();
        Block clicked = e.getClickedBlock();
        if (item == null || item.getType() != Material.END_CRYSTAL || clicked == null) return;
        if (clicked.getType() != Material.OBSIDIAN && clicked.getType() != Material.BEDROCK) return;

        Player p = e.getPlayer();
        if (exempt(p)) return;

        FileConfiguration c = plugin.getConfig();
        PlayerData d = plugin.data(p);
        boolean cancel = false;
        boolean laggy = p.getPing() > c.getInt("max-ping", 350);
        long now = System.nanoTime();

        d.lastPlaceBlock = clicked;

        // Place rate
        d.placeNanos.addLast(now);
        while (!d.placeNanos.isEmpty() && now - d.placeNanos.peekFirst() > 1000 * MS) d.placeNanos.pollFirst();
        int maxPlace = c.getInt("checks.place-rate.max-per-second", 20);
        if (d.placeNanos.size() > maxPlace) {
            vm.flag(p, "AutoCrystal (PlaceRate)", "place-rate", d.placeNanos.size() + " places/s");
            cancel = true;
        }

        Location eyeLoc = p.getEyeLocation();
        Vector eye = eyeLoc.toVector();

        // Place reach
        Vector blockCenter = clicked.getLocation().toVector().add(new Vector(0.5, 0.5, 0.5));
        double reach = eye.distance(blockCenter);
        double maxReach = c.getDouble("checks.place-reach.max", 6.0);
        if (!laggy && reach > maxReach) {
            vm.flag(p, "AutoCrystal (PlaceReach)", "place-reach", String.format("%.2f blocks", reach));
            cancel = true;
        }

        // Placing on a block you are not actually looking at
        if (!laggy && c.getBoolean("checks.place-angle.enabled", true)) {
            RayTraceResult rr = p.rayTraceBlocks(maxReach + 1.0);
            Block looked = rr == null ? null : rr.getHitBlock();
            if (looked == null || !looked.equals(clicked)) {
                vm.flag(p, "AutoCrystal (PlaceAngle)", "place-angle", "not looking at target block");
                cancel = true;
            }
        }

        if (cancel && c.getBoolean("cancel-flagged-actions", true)) {
            e.setCancelled(true);
        }
    }

    // ------------------------------------------------------------ breaking

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof EnderCrystal crystal) || !(e.getDamager() instanceof Player p)) return;
        if (exempt(p)) return;

        FileConfiguration c = plugin.getConfig();
        PlayerData d = plugin.data(p);
        long now = System.nanoTime();
        int ping = p.getPing();
        boolean laggy = ping > c.getInt("max-ping", 350);
        boolean cancel = false;

        Location eyeLoc = p.getEyeLocation();
        Vector eye = eyeLoc.toVector();
        Vector dir = eyeLoc.getDirection();
        BoundingBox box = crystal.getBoundingBox();

        // --- 1. Break rate -------------------------------------------------
        d.breakNanos.addLast(now);
        while (!d.breakNanos.isEmpty() && now - d.breakNanos.peekFirst() > 1000 * MS) d.breakNanos.pollFirst();
        int maxBreak = c.getInt("checks.break-rate.max-per-second", 20);
        if (d.breakNanos.size() > maxBreak) {
            vm.flag(p, "AutoCrystal (Rate)", "break-rate", d.breakNanos.size() + " breaks/s");
            cancel = true;
        }

        // --- 2. Multi-target in a single tick ------------------------------
        int tick = Bukkit.getCurrentTick();
        if (d.lastBreakTick != tick) {
            d.breaksThisTick.clear();
            d.lastBreakTick = tick;
        }
        d.breaksThisTick.add(crystal.getUniqueId());
        if (c.getBoolean("checks.multi-break.enabled", true) && d.breaksThisTick.size() >= 2) {
            vm.flag(p, "CrystalAura (Multi)", "multi-break", d.breaksThisTick.size() + " crystals in 1 tick");
            cancel = true;
        }

        // --- 3. Place -> break speed + consistency -------------------------
        Long spawned = spawnTimes.get(crystal.getUniqueId());
        if (spawned != null) {
            double ms = (now - spawned) / (double) MS;

            // A legit client can't know a crystal exists until the spawn packet arrives, so the
            // fastest possible break is roughly one round trip. Faster = entity-ID prediction / aura.
            double floor = Math.max(c.getDouble("checks.speed.min-ms", 10.0),
                    ping - c.getDouble("checks.speed.ping-tolerance-ms", 40.0));
            if (ms < floor) {
                vm.flag(p, "AutoCrystal (Speed)", "speed", String.format("%.0fms (min %.0fms, ping %dms)", ms, floor, ping));
                cancel = true;
            }

            // Consistency only for crystals this player placed themselves.
            Block below = crystal.getLocation().getBlock().getRelative(0, -1, 0);
            Block below2 = crystal.getLocation().getBlock();
            if (d.lastPlaceBlock != null && (d.lastPlaceBlock.equals(below) || d.lastPlaceBlock.equals(below2))) {
                d.placeToBreakMs.addLast(ms);
                int samples = c.getInt("checks.consistency.samples", 8);
                while (d.placeToBreakMs.size() > samples) d.placeToBreakMs.pollFirst();

                if (d.placeToBreakMs.size() >= samples) {
                    double mean = d.placeToBreakMs.stream().mapToDouble(Double::doubleValue).average().orElse(0);
                    double var = d.placeToBreakMs.stream().mapToDouble(v -> (v - mean) * (v - mean)).average().orElse(0);
                    double std = Math.sqrt(var);
                    if (std < c.getDouble("checks.consistency.max-std-dev-ms", 6.0)
                            && mean < c.getDouble("checks.consistency.max-mean-ms", 200.0)) {
                        vm.flag(p, "AutoCrystal (Consistency)", "consistency",
                                String.format("avg %.0fms, sd %.1fms", mean, std));
                        d.placeToBreakMs.clear();
                    }
                }
            }
        }

        // --- 4. Reach ------------------------------------------------------
        Vector nearest = new Vector(
                clamp(eye.getX(), box.getMinX(), box.getMaxX()),
                clamp(eye.getY(), box.getMinY(), box.getMaxY()),
                clamp(eye.getZ(), box.getMinZ(), box.getMaxZ()));
        double reach = eye.distance(nearest);

        if (!laggy) {
            if (reach > c.getDouble("checks.reach.max", 4.6)) {
                vm.flag(p, "CrystalAura (Reach)", "reach", String.format("%.2f blocks", reach));
                cancel = true;
            }

            // --- 5. Hitting a crystal you are not looking at ----------------
            BoundingBox grown = box.clone().expand(c.getDouble("checks.angle.hitbox-expand", 0.4));
            RayTraceResult look = grown.rayTrace(eye, dir, reach + 3.0);
            if (look == null) {
                double off = Math.toDegrees(dir.angle(box.getCenter().subtract(eye)));
                vm.flag(p, "CrystalAura (Angle)", "angle", String.format("%.0f deg off target", off));
                cancel = true;
            }

            // --- 6. Hitting through walls ----------------------------------
            if (c.getBoolean("checks.wall.enabled", true) && reach > 0.5) {
                Vector toNearest = nearest.clone().subtract(eye);
                double dist = toNearest.length();
                RayTraceResult wall = p.getWorld().rayTraceBlocks(eyeLoc, toNearest.normalize(), dist,
                        FluidCollisionMode.NEVER, true);
                if (wall != null && wall.getHitBlock() != null
                        && wall.getHitPosition().distance(eye) < dist - 0.6) {
                    vm.flag(p, "CrystalAura (Wall)", "wall", "hit through " + wall.getHitBlock().getType().name().toLowerCase());
                    cancel = true;
                }
            }
        }

        // --- 7. Rotation snap right before the hit -------------------------
        if (now - d.snapNanos < 75 * MS && d.snapDelta > c.getDouble("checks.snap.degrees", 100.0)) {
            vm.flag(p, "CrystalAura (Snap)", "snap", String.format("%.0f deg snap", d.snapDelta));
            d.snapDelta = 0;
        }

        if (cancel && c.getBoolean("cancel-flagged-actions", true)) {
            e.setCancelled(true);
        }
    }

    // ------------------------------------------------------------ helpers

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private static float wrap(float deg) {
        return ((deg % 360f) + 540f) % 360f - 180f;
    }
}
