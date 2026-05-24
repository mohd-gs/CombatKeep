package com.combatkeep.mod;

import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Manages the combat timer boss bar display for combat-tagged players.
 *
 * Each player in combat gets a red boss bar at the top of their screen
 * showing the remaining combat time. The bar:
 * - Starts RED with full progress
 * - Turns YELLOW when 7 seconds or less remain
 * - Turns WHITE/flashy when 3 seconds or less remain
 * - Disappears when combat ends
 */
public class CombatBossBarManager {

	/** Map of player UUID -> their boss bar instance */
	private final Map<UUID, ServerBossBar> playerBossBars = new HashMap<>();

	/**
	 * Add a player to the boss bar display when they enter combat.
	 * If they already have a boss bar, it will be refreshed.
	 */
	public void addPlayer(ServerPlayerEntity player) {
		UUID uuid = player.getUuid();

		// Remove existing boss bar if present
		removePlayer(player);

		// Calculate remaining time
		int remaining = CombatKeepMod.getRemainingCombatTime(player);
		float progress = calculateProgress(remaining);

		// Create a new boss bar
		ServerBossBar bossBar = new ServerBossBar(
			createCombatText(remaining),
			BossBar.Color.RED,
			BossBar.Style.NOTCHED_10
		);

		bossBar.setPercent(progress);
		bossBar.setDarkenSky(false);
		bossBar.setDragonMusic(false);
		bossBar.setThickenFog(false);

		// Add the player as a viewer
		bossBar.addPlayer(player);

		playerBossBars.put(uuid, bossBar);
	}

	/**
	 * Remove a player's boss bar when combat ends or they disconnect.
	 */
	public void removePlayer(ServerPlayerEntity player) {
		UUID uuid = player.getUuid();
		ServerBossBar bossBar = playerBossBars.remove(uuid);
		if (bossBar != null) {
			bossBar.removePlayer(player);
			bossBar.clearPlayers();
		}
	}

	/**
	 * Update the boss bar for a player with the current remaining combat time.
	 * This also handles color transitions based on remaining time.
	 */
	public void updateBossBar(ServerPlayerEntity player, int remainingSeconds) {
		UUID uuid = player.getUuid();
		ServerBossBar bossBar = playerBossBars.get(uuid);

		if (bossBar == null) {
			// Player doesn't have a boss bar yet - create one
			addPlayer(player);
			return;
		}

		// Calculate progress
		float progress = calculateProgress(remainingSeconds);
		bossBar.setPercent(progress);

		// Update color and text based on remaining time
		if (remainingSeconds <= 3) {
			// Critical: white, bold text
			bossBar.setColor(BossBar.Color.WHITE);
			bossBar.setName(
				Text.literal("⚠ COMBAT ENDING: " + remainingSeconds + "s ⚠")
					.formatted(Formatting.WHITE, Formatting.BOLD)
			);
		} else if (remainingSeconds <= 7) {
			// Warning: yellow
			bossBar.setColor(BossBar.Color.YELLOW);
			bossBar.setName(
				Text.literal("⚔ Combat Tag: " + remainingSeconds + "s")
					.formatted(Formatting.YELLOW, Formatting.BOLD)
			);
		} else {
			// Active: red
			bossBar.setColor(BossBar.Color.RED);
			bossBar.setName(
				Text.literal("⚔ Combat Tag: " + remainingSeconds + "s")
					.formatted(Formatting.RED, Formatting.BOLD)
			);
		}
	}

	/**
	 * Calculate the boss bar progress (0.0 to 1.0) based on remaining time.
	 */
	private float calculateProgress(int remainingSeconds) {
		if (remainingSeconds <= 0) return 0.0f;
		if (remainingSeconds >= CombatKeepMod.COMBAT_TAG_DURATION_SECONDS) return 1.0f;
		return (float) remainingSeconds / CombatKeepMod.COMBAT_TAG_DURATION_SECONDS;
	}

	/**
	 * Create the combat text for the boss bar.
	 */
	private Text createCombatText(int remainingSeconds) {
		return Text.literal("⚔ Combat Tag: " + remainingSeconds + "s")
			.formatted(Formatting.RED, Formatting.BOLD);
	}

	/**
	 * Check if a player has an active boss bar.
	 */
	public boolean hasBossBar(UUID uuid) {
		return playerBossBars.containsKey(uuid);
	}

	/**
	 * Get the number of active boss bars.
	 */
	public int getActiveBossBarCount() {
		return playerBossBars.size();
	}
}
