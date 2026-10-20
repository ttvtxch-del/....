package com.privprac.ac;

import org.bukkit.block.Block;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Per-player tracking. Only ever touched from the main server thread. */
final class PlayerData {

    final Map<String, Double> vl = new HashMap<>();
    final Map<String, Long> lastAlert = new HashMap<>();

    // Crystal breaking
    final ArrayDeque<Long> breakNanos = new ArrayDeque<>();
    final ArrayDeque<Double> placeToBreakMs = new ArrayDeque<>();
    final Set<UUID> breaksThisTick = new HashSet<>();
    int lastBreakTick = -1;

    // Crystal placing
    final ArrayDeque<Long> placeNanos = new ArrayDeque<>();
    Block lastPlaceBlock;

    // Rotation snap tracking
    float snapDelta;
    long snapNanos;
}
