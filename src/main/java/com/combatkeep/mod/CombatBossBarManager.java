package com.combatkeep.mod;

import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.world.BossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
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
        private final Map<UUID, ServerBossEvent> playerBossBars = new HashMap<>();

        /**
         * Add a player to the boss bar display when they enter combat.
         * If they already have a boss bar, it will be refreshed.
         */
        public void addPlayer(ServerPlayer player) {
                UUID uuid = player.getUUID();

                // Remove existing boss bar if present
                removePlayer(player);

                // Calculate remaining time
                int remaining = CombatKeepMod.getRemainingCombatTime(player);
                float progress = calculateProgress(remaining);

                // Create a new boss bar (requires UUID, Component, Color, Overlay)
                ServerBossEvent bossBar = new ServerBossEvent(
                        UUID.randomUUID(),
                        createCombatText(remaining),
                        BossEvent.BossBarColor.RED,
                        BossEvent.BossBarOverlay.NOTCHED_10
                );

                bossBar.setProgress(progress);
                bossBar.setDarkenScreen(false);
                bossBar.setPlayBossMusic(false);
                bossBar.setCreateWorldFog(false);

                // Add the player as a viewer
                bossBar.addPlayer(player);

                playerBossBars.put(uuid, bossBar);
        }

        /**
         * Remove a player's boss bar when combat ends or they disconnect.
         */
        public void removePlayer(ServerPlayer player) {
                UUID uuid = player.getUUID();
                ServerBossEvent bossBar = playerBossBars.remove(uuid);
                if (bossBar != null) {
                        bossBar.removePlayer(player);
                        bossBar.removeAllPlayers();
                }
        }

        /**
         * Remove a boss bar by UUID when the player is offline.
         * This prevents memory leaks when a combat-tagged player disconnects
         * and their boss bar cannot be removed via removePlayer().
         */
        public void removeByUuid(UUID uuid) {
                ServerBossEvent bossBar = playerBossBars.remove(uuid);
                if (bossBar != null) {
                        bossBar.removeAllPlayers();
                }
        }

        /**
         * Update the boss bar for a player with the current remaining combat time.
         * This also handles color transitions based on remaining time.
         */
        public void updateBossBar(ServerPlayer player, int remainingSeconds) {
                UUID uuid = player.getUUID();
                ServerBossEvent bossBar = playerBossBars.get(uuid);

                if (bossBar == null) {
                        // Player doesn't have a boss bar yet - create one
                        addPlayer(player);
                        return;
                }

                // Calculate progress
                float progress = calculateProgress(remainingSeconds);
                bossBar.setProgress(progress);

                // Update color and text based on remaining time
                if (remainingSeconds <= 3) {
                        bossBar.setColor(BossEvent.BossBarColor.WHITE);
                        bossBar.setName(
                                Component.literal("⚠ COMBAT ENDING: " + remainingSeconds + "s ⚠")
                                        .withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD)
                        );
                } else if (remainingSeconds <= 7) {
                        bossBar.setColor(BossEvent.BossBarColor.YELLOW);
                        bossBar.setName(
                                Component.literal("⚔ Combat Tag: " + remainingSeconds + "s")
                                        .withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)
                        );
                } else {
                        bossBar.setColor(BossEvent.BossBarColor.RED);
                        bossBar.setName(
                                Component.literal("⚔ Combat Tag: " + remainingSeconds + "s")
                                        .withStyle(ChatFormatting.RED, ChatFormatting.BOLD)
                        );
                }
        }

        /**
         * Calculate the boss bar progress (0.0 to 1.0) based on remaining time.
         */
        private float calculateProgress(int remainingSeconds) {
                int maxDuration = CombatKeepMod.getCombatTagDurationSeconds();
                if (remainingSeconds <= 0) return 0.0f;
                if (remainingSeconds >= maxDuration) return 1.0f;
                return (float) remainingSeconds / maxDuration;
        }

        /**
         * Create the combat text for the boss bar.
         */
        private Component createCombatText(int remainingSeconds) {
                return Component.literal("⚔ Combat Tag: " + remainingSeconds + "s")
                        .withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
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

        /**
         * Get all UUIDs that currently have active boss bars.
         * Used for bulk cleanup on server stop.
         */
        public Set<UUID> getAllUuids() {
                return new HashSet<>(playerBossBars.keySet());
        }
}
