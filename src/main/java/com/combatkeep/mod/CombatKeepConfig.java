package com.combatkeep.mod;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

/**
 * Configuration manager for CombatKeep.
 *
 * Generates {@code config/combatkeep/combatkeep.properties} on first run
 * with detailed comments. All values are clamped to safe ranges on load.
 * Use {@code /combatkeep reload} to apply changes without restarting.
 */
public class CombatKeepConfig {

	private static final Logger LOGGER = LoggerFactory.getLogger("combatkeep/config");
	private static final Path CONFIG_DIR = Path.of("config", "combatkeep");
	private static final Path CONFIG_FILE = CONFIG_DIR.resolve("combatkeep.properties");

	// ── Configuration values with defaults ──

	/** Combat tag duration in seconds */
	private int combatTagDurationSeconds = 15;

	/** Percentage of items to drop on combat death */
	private int combatDeathDropPercent = 50;

	/** Percentage of victim balance to transfer to killer via Solidus */
	private double solidusDeathPenaltyPercent = 10.0;

	/** Minimum penalty — if 10% < this and balance > 0, charge this instead */
	private double solidusMinPenalty = 0.0;

	/** Respawn immunity in seconds (0 = disabled) */
	private int respawnImmunitySeconds = 10;

	/** Whether to apply Solidus economy penalty when a player combat-logs */
	private boolean solidusCombatLogPenalty = true;

	private static CombatKeepConfig instance;

	public static CombatKeepConfig getInstance() {
		if (instance == null) {
			instance = new CombatKeepConfig();
		}
		return instance;
	}

	private CombatKeepConfig() {}

	// ── Load / Save ──

	public void load() {
		try {
			Files.createDirectories(CONFIG_DIR);

			if (Files.exists(CONFIG_FILE)) {
				Properties props = new Properties();
				try (InputStream is = Files.newInputStream(CONFIG_FILE)) {
					props.load(is);
				}

				combatTagDurationSeconds = clamp(getInt(props, "combatTagDurationSeconds", 15), 5, 60);
				combatDeathDropPercent = clamp(getInt(props, "combatDeathDropPercent", 50), 10, 100);
				solidusDeathPenaltyPercent = clamp(getDouble(props, "solidusDeathPenaltyPercent", 10.0), 0.0, 50.0);
				solidusMinPenalty = clamp(getDouble(props, "solidusMinPenalty", 0.0), 0.0, 1000000.0);
				respawnImmunitySeconds = clamp(getInt(props, "respawnImmunitySeconds", 10), 0, 30);
				solidusCombatLogPenalty = getBoolean(props, "solidusCombatLogPenalty", true);

				LOGGER.info("Configuration loaded from {}", CONFIG_FILE);
			} else {
				save();
				LOGGER.info("Default configuration generated at {}", CONFIG_FILE);
			}
		} catch (IOException e) {
			LOGGER.error("Failed to load configuration — using defaults", e);
		}
	}

	public void save() {
		try {
			Files.createDirectories(CONFIG_DIR);

			try (BufferedWriter writer = Files.newBufferedWriter(CONFIG_FILE)) {
				writer.write("# CombatKeep Configuration");
				writer.newLine();
				writer.write("# Auto-generated on first run. Edit and run /combatkeep reload to apply.");
				writer.newLine();
				writer.newLine();

				writer.write("# Combat tag duration in seconds (range: 5-60)");
				writer.newLine();
				writer.write("combatTagDurationSeconds=" + combatTagDurationSeconds);
				writer.newLine();
				writer.newLine();

				writer.write("# Percentage of items to drop on combat death (range: 10-100)");
				writer.newLine();
				writer.write("combatDeathDropPercent=" + combatDeathDropPercent);
				writer.newLine();
				writer.newLine();

				writer.write("# Percentage of victim balance to transfer to killer via Solidus (range: 0-50, 0 = disabled)");
				writer.newLine();
				writer.write("solidusDeathPenaltyPercent=" + (long) solidusDeathPenaltyPercent);
				writer.newLine();
				writer.newLine();

				writer.write("# Minimum economy penalty amount — if the percentage is below this and balance > 0, charge this instead (0 = no minimum)");
				writer.newLine();
				writer.write("solidusMinPenalty=" + (long) solidusMinPenalty);
				writer.newLine();
				writer.newLine();

				writer.write("# Respawn immunity in seconds — prevents combat tagging but NOT damage (range: 0-30, 0 = disabled)");
				writer.newLine();
				writer.write("respawnImmunitySeconds=" + respawnImmunitySeconds);
				writer.newLine();
				writer.newLine();

				writer.write("# Whether to apply Solidus economy penalty when a player combat-logs (true/false)");
				writer.newLine();
				writer.write("solidusCombatLogPenalty=" + solidusCombatLogPenalty);
				writer.newLine();
			}
		} catch (IOException e) {
			LOGGER.error("Failed to save configuration", e);
		}
	}

	// ── Helpers ──

	private int getInt(Properties props, String key, int defaultVal) {
		try {
			return Integer.parseInt(props.getProperty(key, String.valueOf(defaultVal)));
		} catch (NumberFormatException e) {
			return defaultVal;
		}
	}

	private double getDouble(Properties props, String key, double defaultVal) {
		try {
			return Double.parseDouble(props.getProperty(key, String.valueOf(defaultVal)));
		} catch (NumberFormatException e) {
			return defaultVal;
		}
	}

	private boolean getBoolean(Properties props, String key, boolean defaultVal) {
		return Boolean.parseBoolean(props.getProperty(key, String.valueOf(defaultVal)));
	}

	private int clamp(int val, int min, int max) {
		return Math.max(min, Math.min(max, val));
	}

	private double clamp(double val, double min, double max) {
		return Math.max(min, Math.min(max, val));
	}

	// ── Getters ──

	public int getCombatTagDurationSeconds() {
		return combatTagDurationSeconds;
	}

	public int getCombatDeathDropPercent() {
		return combatDeathDropPercent;
	}

	public double getSolidusDeathPenaltyPercent() {
		return solidusDeathPenaltyPercent;
	}

	public double getSolidusMinPenalty() {
		return solidusMinPenalty;
	}

	public int getRespawnImmunitySeconds() {
		return respawnImmunitySeconds;
	}

	public boolean isSolidusCombatLogPenalty() {
		return solidusCombatLogPenalty;
	}
}
