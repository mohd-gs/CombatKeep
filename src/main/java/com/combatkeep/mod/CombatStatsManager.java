package com.combatkeep.mod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages combat statistics persistence using Gson + JSON.
 *
 * Stats file: {@code config/combatkeep/stats.json}
 * Loaded on startup, saved every 5 minutes and on server stop.
 * All operations are thread-safe via {@link ConcurrentHashMap}.
 */
public class CombatStatsManager {

	private static final Logger LOGGER = LoggerFactory.getLogger("combatkeep/stats");
	private static final Path STATS_DIR = Path.of("config", "combatkeep");
	private static final Path STATS_FILE = STATS_DIR.resolve("stats.json");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private static final Map<UUID, PlayerStats> statsMap = new ConcurrentHashMap<>();

	// ── Load / Save ──

	public static void load() {
		try {
			Files.createDirectories(STATS_DIR);
			if (Files.exists(STATS_FILE)) {
				Type type = new TypeToken<Map<String, PlayerStats>>() {}.getType();
				Map<String, PlayerStats> loaded = GSON.fromJson(
					Files.newBufferedReader(STATS_FILE), type
				);
				if (loaded != null) {
					for (Map.Entry<String, PlayerStats> entry : loaded.entrySet()) {
						try {
							statsMap.put(UUID.fromString(entry.getKey()), entry.getValue());
						} catch (IllegalArgumentException e) {
							LOGGER.warn("Skipping invalid UUID in stats file: {}", entry.getKey());
						}
					}
				}
				LOGGER.info("Loaded combat stats for {} players", statsMap.size());
			}
		} catch (Exception e) {
			LOGGER.error("Failed to load combat stats — starting fresh", e);
		}
	}

	public static void save() {
		try {
			Files.createDirectories(STATS_DIR);
			Map<String, PlayerStats> serializable = new LinkedHashMap<>();
			for (Map.Entry<UUID, PlayerStats> entry : statsMap.entrySet()) {
				serializable.put(entry.getKey().toString(), entry.getValue());
			}
			try (Writer writer = Files.newBufferedWriter(STATS_FILE)) {
				GSON.toJson(serializable, writer);
			}
			LOGGER.debug("Saved combat stats for {} players", statsMap.size());
		} catch (Exception e) {
			LOGGER.error("Failed to save combat stats", e);
		}
	}

	// ── Stats Access ──

	/**
	 * Get stats for a player, creating a new entry if none exists.
	 */
	public static PlayerStats getStats(UUID uuid) {
		return statsMap.computeIfAbsent(uuid, k -> new PlayerStats());
	}

	/**
	 * Update the cached player name for a UUID.
	 */
	public static void updatePlayerName(UUID uuid, String name) {
		PlayerStats stats = getStats(uuid);
		stats.playerName = name;
	}

	/**
	 * Record a combat kill: increment kills, update streak, track economy gained.
	 *
	 * @param killer         killer's UUID
	 * @param killerName     killer's display name
	 * @param economyGained  amount gained from the kill (0 if Solidus not installed)
	 */
	public static void recordKill(UUID killer, String killerName, double economyGained) {
		PlayerStats stats = getStats(killer);
		stats.playerName = killerName;
		stats.addKill();
		if (economyGained > 0) {
			stats.addEconomyGained(economyGained);
		}
	}

	/**
	 * Record a combat death: increment deaths, reset streak, track economy lost.
	 *
	 * @param victim        victim's UUID
	 * @param victimName    victim's display name
	 * @param economyLost   amount lost from the death (0 if Solidus not installed)
	 */
	public static void recordDeath(UUID victim, String victimName, double economyLost) {
		PlayerStats stats = getStats(victim);
		stats.playerName = victimName;
		stats.addDeath();
		if (economyLost > 0) {
			stats.addEconomyLost(economyLost);
		}
	}

	/**
	 * Clear all in-memory stats (server stop cleanup).
	 */
	public static void clearAll() {
		statsMap.clear();
	}

	/**
	 * Number of tracked players.
	 */
	public static int getTrackedPlayerCount() {
		return statsMap.size();
	}
}
