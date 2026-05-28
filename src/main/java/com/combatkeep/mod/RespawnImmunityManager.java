package com.combatkeep.mod;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

import java.util.*;

/**
 * Manages respawn immunity — a grace period after respawning during which
 * the player CANNOT be combat-tagged, but CAN still take damage.
 *
 * Immunity duration is read from {@link CombatKeepConfig# getRespawnImmunitySeconds()}.
 * A countdown is displayed on the action bar. When immunity expires the player
 * is notified in chat.
 */
public class RespawnImmunityManager {

	/** Players with active immunity: UUID -> immunity end tick */
	private static final Map<UUID, Long> immunePlayers = new HashMap<>();

	/**
	 * Grant respawn immunity to a player.
	 *
	 * @param player  the player who just respawned
	 * @param seconds immunity duration (game-seconds); ignored if <= 0
	 */
	public static void grantImmunity(ServerPlayer player, int seconds) {
		if (seconds <= 0) return;
		MinecraftServer server = player.level().getServer();
		if (server == null) return;

		long endTick = server.getTickCount() + (seconds * 20L);
		immunePlayers.put(player.getUUID(), endTick);

		player.sendSystemMessage(
			Component.literal("Respawn immunity active for " + seconds + "s — you cannot enter combat")
				.withStyle(ChatFormatting.GREEN)
		);
	}

	/**
	 * Check whether a player currently has respawn immunity.
	 * Expired entries are cleaned up automatically.
	 */
	public static boolean isImmune(ServerPlayer player) {
		Long endTick = immunePlayers.get(player.getUUID());
		if (endTick == null) return false;
		MinecraftServer server = player.level().getServer();
		if (server == null) return false;
		if (server.getTickCount() >= endTick) {
			immunePlayers.remove(player.getUUID());
			return false;
		}
		return true;
	}

	/**
	 * Get remaining immunity time in seconds. Returns 0 if not immune.
	 */
	public static int getRemainingImmunity(ServerPlayer player) {
		Long endTick = immunePlayers.get(player.getUUID());
		if (endTick == null) return 0;
		MinecraftServer server = player.level().getServer();
		if (server == null) return 0;
		long remaining = endTick - server.getTickCount();
		return remaining > 0 ? (int) Math.ceil(remaining / 20.0) : 0;
	}

	/**
	 * Tick handler — clean up expired immunities and display action-bar countdown.
	 * Called every server tick from {@link CombatKeepMod}.
	 */
	public static void tick(MinecraftServer server) {
		if (immunePlayers.isEmpty()) return;

		long currentTick = server.getTickCount();

		Iterator<Map.Entry<UUID, Long>> iterator = immunePlayers.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<UUID, Long> entry = iterator.next();
			UUID uuid = entry.getKey();
			long endTick = entry.getValue();

			if (currentTick >= endTick) {
				iterator.remove();
				ServerPlayer player = server.getPlayerList().getPlayer(uuid);
				if (player != null) {
					player.sendSystemMessage(
						Component.literal("Respawn immunity expired — you can now enter combat")
							.withStyle(ChatFormatting.YELLOW)
					);
				}
			} else {
				// Show action-bar countdown every second (every 20 ticks)
				if (currentTick % 20 == 0) {
					ServerPlayer player = server.getPlayerList().getPlayer(uuid);
					if (player != null) {
						int remaining = (int) Math.ceil((endTick - currentTick) / 20.0);
						player.displayClientMessage(
							Component.literal("Immunity: " + remaining + "s")
								.withStyle(ChatFormatting.GREEN),
							true // action bar
						);
					}
				}
			}
		}
	}

	/**
	 * Remove immunity for a specific player (e.g. on disconnect).
	 */
	public static void removeImmunity(UUID uuid) {
		immunePlayers.remove(uuid);
	}

	/**
	 * Clear all immunity entries (server stop).
	 */
	public static void clearAll() {
		immunePlayers.clear();
	}
}
