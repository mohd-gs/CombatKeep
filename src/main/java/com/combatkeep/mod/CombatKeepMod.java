package com.combatkeep.mod;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * CombatKeep - A server-side Fabric mod for Minecraft 26.1.2
 *
 * Features:
 * - KeepInventory is automatically enforced
 * - On normal death: player loses only XP
 * - On combat death (dying while combat-tagged): player loses 50% of inventory + all XP
 * - Armor and shulker boxes are exempt from the 50% drop
 * - Disconnecting while combat-tagged triggers the same penalty as combat death
 * - Combat tag lasts 15 seconds and refreshes on each hit between players
 * - Boss bar shows remaining combat time to both players
 */
public class CombatKeepMod implements ModInitializer {

        public static final String MOD_ID = "combatkeep";
        public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

        /** Combat tag duration in seconds */
        public static final int COMBAT_TAG_DURATION_SECONDS = 15;

        /** Combat tag duration in milliseconds */
        public static final long COMBAT_TAG_DURATION_MS = COMBAT_TAG_DURATION_SECONDS * 1000L;

        // ========== Combat Tracking ==========

        /** Players currently in combat: UUID -> combat end timestamp (millis) */
        private static final Map<UUID, Long> combatTaggedPlayers = new HashMap<>();

        /** Combat pairs: player UUID -> opponent UUID */
        private static final Map<UUID, UUID> combatPartners = new HashMap<>();

        // ========== Death Data Tracking ==========

        /** Data about players who died and need penalty processing on respawn */
        private static final Map<UUID, DeathData> pendingDeathPenalties = new HashMap<>();

        /** Boss bar manager */
        private static CombatBossBarManager bossBarManager;

        // ========== Data Classes ==========

        /**
         * Stores data about a player's death for processing on respawn.
         */
        private static class DeathData {
                final Vec3d deathPosition;
                final ServerWorld deathWorld;
                final boolean wasCombatTagged;

                DeathData(Vec3d pos, ServerWorld world, boolean combatTagged) {
                        this.deathPosition = pos;
                        this.deathWorld = world;
                        this.wasCombatTagged = combatTagged;
                }
        }

        @Override
        public void onInitialize() {
                LOGGER.info("CombatKeep mod initializing for Minecraft 26.1.2...");

                bossBarManager = new CombatBossBarManager();

                // Force keepInventory game rule
                ServerLifecycleEvents.SERVER_STARTED.register(this::forceKeepInventory);
                ServerTickEvents.END_SERVER_TICK.register(server -> {
                        if (server.getTicks() % 200 == 0) {
                                forceKeepInventory(server);
                        }
                });

                // Register event callbacks
                registerAttackCallback();
                registerDeathCallback();
                registerRespawnCallback();
                registerDisconnectCallback();
                registerCombatTagChecker();

                LOGGER.info("CombatKeep mod initialized!");
                LOGGER.info("  - KeepInventory: enforced");
                LOGGER.info("  - Combat tag: {} seconds", COMBAT_TAG_DURATION_SECONDS);
                LOGGER.info("  - Combat death penalty: 50% inventory drop + all XP");
                LOGGER.info("  - Protected items: armor, shulker boxes");
                LOGGER.info("  - Combat quit penalty: same as combat death");
        }

        // ========== KeepInventory Enforcement ==========

        /**
         * Force the keepInventory game rule to true on all worlds.
         */
        private void forceKeepInventory(MinecraftServer server) {
                for (ServerWorld world : server.getWorlds()) {
                        GameRules.BooleanRule rule = world.getGameRules().get(GameRules.KEEP_INVENTORY);
                        if (!rule.get()) {
                                rule.set(true, server);
                                LOGGER.info("Forced keepInventory=true in world: {}", world.getRegistryKey().getValue());
                        }
                }
        }

        // ========== Event Callbacks ==========

        /**
         * When a player attacks another player, both get combat-tagged.
         * The 15-second timer refreshes on each hit.
         */
        private void registerAttackCallback() {
                AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
                        if (world.isClient()) return ActionResult.PASS;

                        if (entity instanceof ServerPlayerEntity target) {
                                ServerPlayerEntity attacker = (ServerPlayerEntity) player;

                                // Skip creative/spectator mode players
                                if (attacker.isCreative() || attacker.isSpectator()) return ActionResult.PASS;
                                if (target.isCreative() || target.isSpectator()) return ActionResult.PASS;

                                tagPlayerForCombat(attacker, target);
                        }

                        return ActionResult.PASS;
                });
        }

        /**
         * On player death: store death data for processing on respawn.
         * - Combat death: will lose 50% items + XP on respawn
         * - Normal death: will lose only XP on respawn
         */
        private void registerDeathCallback() {
                ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
                        if (!(entity instanceof ServerPlayerEntity player)) return;

                        boolean wasCombatTagged = isCombatTagged(player);

                        // Store death data for respawn processing
                        pendingDeathPenalties.put(player.getUuid(), new DeathData(
                                player.getPos(),
                                player.getServerWorld(),
                                wasCombatTagged
                        ));

                        if (wasCombatTagged) {
                                LOGGER.info("Player '{}' died while combat-tagged", player.getNameForScoreboard());
                                removeCombatTag(player);
                        } else {
                                LOGGER.info("Player '{}' died normally (XP only loss)", player.getNameForScoreboard());
                        }
                });
        }

        /**
         * On player respawn: apply the appropriate death penalty.
         * This is where we actually modify the player's inventory and XP,
         * because at this point the new player entity is fully initialized.
         */
        private void registerRespawnCallback() {
                ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
                        UUID uuid = newPlayer.getUuid();
                        DeathData deathData = pendingDeathPenalties.remove(uuid);

                        if (deathData == null) return;

                        if (deathData.wasCombatTagged) {
                                // Combat death: lose 50% items + all XP
                                applyCombatPenalty(newPlayer, deathData.deathPosition, deathData.deathWorld);
                        } else {
                                // Normal death: lose only XP
                                applyNormalDeathPenalty(newPlayer);
                        }
                });
        }

        /**
         * On player disconnect: if combat-tagged, apply combat quit penalty.
         * Also notify the opponent that their combat partner has logged out.
         */
        private void registerDisconnectCallback() {
                ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
                        ServerPlayerEntity player = handler.getPlayer();
                        if (isCombatTagged(player)) {
                                LOGGER.info("Player '{}' disconnected while combat-tagged - applying quit penalty",
                                        player.getNameForScoreboard());

                                // Notify the opponent before removing the tag
                                UUID opponentUuid = combatPartners.get(player.getUuid());
                                if (opponentUuid != null) {
                                        ServerPlayerEntity opponent = server.getPlayerManager().getPlayer(opponentUuid);
                                        if (opponent != null) {
                                                opponent.sendMessage(
                                                        Text.literal(player.getNameForScoreboard() + " combat-logged! Their items have been dropped.")
                                                                .formatted(Formatting.GOLD),
                                                        false
                                                );
                                                // Also remove the combat tag from the opponent since the fight is over
                                                removeCombatTag(opponent);
                                        }
                                }

                                applyCombatQuitPenalty(player);
                                removeCombatTag(player);
                        }
                });
        }

        /**
         * Check for expired combat tags and update boss bars every tick.
         */
        private void registerCombatTagChecker() {
                ServerTickEvents.END_SERVER_TICK.register(server -> {
                        long now = System.currentTimeMillis();
                        Iterator<Map.Entry<UUID, Long>> iterator = combatTaggedPlayers.entrySet().iterator();

                        while (iterator.hasNext()) {
                                Map.Entry<UUID, Long> entry = iterator.next();
                                UUID uuid = entry.getKey();
                                long endTime = entry.getValue();

                                ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);

                                if (now >= endTime) {
                                        // Combat tag expired
                                        iterator.remove();
                                        combatPartners.remove(uuid);

                                        if (player != null) {
                                                bossBarManager.removePlayer(player);
                                                player.sendMessage(
                                                        Text.literal("Combat Tag expired - you are safe!")
                                                                .formatted(Formatting.GREEN),
                                                        true
                                                );
                                        }
                                } else if (player != null) {
                                        // Update boss bar
                                        int remaining = (int) Math.ceil((endTime - now) / 1000.0);
                                        bossBarManager.updateBossBar(player, remaining);
                                }
                        }
                });
        }

        // ========== Combat Tag Management ==========

        /**
         * Tag both players for combat. Timer resets to 15 seconds on each call.
         */
        public static void tagPlayerForCombat(ServerPlayerEntity attacker, ServerPlayerEntity target) {
                long combatEnd = System.currentTimeMillis() + COMBAT_TAG_DURATION_MS;

                combatTaggedPlayers.put(attacker.getUuid(), combatEnd);
                combatPartners.put(attacker.getUuid(), target.getUuid());

                combatTaggedPlayers.put(target.getUuid(), combatEnd);
                combatPartners.put(target.getUuid(), attacker.getUuid());

                // Show boss bars
                bossBarManager.addPlayer(attacker);
                bossBarManager.addPlayer(target);

                // Action bar notification
                String attackerName = attacker.getNameForScoreboard();
                String targetName = target.getNameForScoreboard();

                attacker.sendMessage(
                        Text.literal("⚔ Combat Tag vs " + targetName + " - 15s!")
                                .formatted(Formatting.RED, Formatting.BOLD),
                        true
                );
                target.sendMessage(
                        Text.literal("⚔ Combat Tag vs " + attackerName + " - 15s!")
                                .formatted(Formatting.RED, Formatting.BOLD),
                        true
                );
        }

        /**
         * Check if a player is currently combat-tagged.
         */
        public static boolean isCombatTagged(ServerPlayerEntity player) {
                Long endTime = combatTaggedPlayers.get(player.getUuid());
                return endTime != null && System.currentTimeMillis() < endTime;
        }

        /**
         * Get remaining combat time in seconds.
         */
        public static int getRemainingCombatTime(ServerPlayerEntity player) {
                Long endTime = combatTaggedPlayers.get(player.getUuid());
                if (endTime == null) return 0;
                long remaining = endTime - System.currentTimeMillis();
                return remaining > 0 ? (int) Math.ceil(remaining / 1000.0) : 0;
        }

        /**
         * Remove combat tag from a player.
         */
        public static void removeCombatTag(ServerPlayerEntity player) {
                combatTaggedPlayers.remove(player.getUuid());
                combatPartners.remove(player.getUuid());
                bossBarManager.removePlayer(player);
        }

        // ========== Penalty Systems ==========

        /**
         * Normal death penalty: player loses all XP only.
         * Items are kept via keepInventory.
         */
        private void applyNormalDeathPenalty(ServerPlayerEntity player) {
                player.setExperienceLevel(0);
                player.setExperiencePoints(0);

                player.sendMessage(
                        Text.literal("You died! Lost all experience points.")
                                .formatted(Formatting.YELLOW),
                        true
                );

                LOGGER.info("Normal death penalty applied to '{}': XP lost", player.getNameForScoreboard());
        }

        /**
         * Combat death penalty: player loses 50% of inventory items + all XP.
         * Items are dropped at the death location.
         *
         * @param player     The respawned player (new entity)
         * @param deathPos   Where the player died
         * @param deathWorld The world where the player died
         */
        private void applyCombatPenalty(ServerPlayerEntity player, Vec3d deathPos, ServerWorld deathWorld) {
                // Lose all XP
                player.setExperienceLevel(0);
                player.setExperiencePoints(0);

                // Get droppable slots (excludes armor and shulker boxes)
                List<Integer> droppableSlots = getDroppableSlots(player);

                if (droppableSlots.isEmpty()) {
                        player.sendMessage(
                                Text.literal("You died in combat! Lost all XP but had no items to drop.")
                                        .formatted(Formatting.RED, Formatting.BOLD),
                                true
                        );
                        LOGGER.info("Combat penalty applied to '{}': XP lost, no droppable items", player.getNameForScoreboard());
                        return;
                }

                // Calculate 50% (rounded up)
                int totalDroppable = droppableSlots.size();
                int itemsToDrop = (int) Math.ceil(totalDroppable * 0.5);

                // Randomly shuffle
                Collections.shuffle(droppableSlots);

                // Remove and drop items
                int dropped = 0;
                for (int slot : droppableSlots) {
                        if (dropped >= itemsToDrop) break;

                        ItemStack stack = player.getInventory().getStack(slot);
                        if (!stack.isEmpty()) {
                                spawnItemDrop(deathWorld, deathPos, stack.copy());
                                player.getInventory().setStack(slot, ItemStack.EMPTY);
                                dropped++;
                        }
                }

                player.sendMessage(
                        Text.literal("You died in combat! Lost " + dropped + " items and all XP!")
                                .formatted(Formatting.RED, Formatting.BOLD),
                        true
                );

                LOGGER.info("Combat penalty applied to '{}': dropped {}/{} items, XP lost",
                        player.getNameForScoreboard(), dropped, totalDroppable);
        }

        /**
         * Combat quit penalty: same as combat death - lose 50% of items + all XP.
         * Applied when a player disconnects while combat-tagged.
         * Items are dropped at the player's current location before they leave.
         */
        private void applyCombatQuitPenalty(ServerPlayerEntity player) {
                Vec3d pos = player.getPos();
                ServerWorld world = player.getServerWorld();

                // Lose all XP
                player.setExperienceLevel(0);
                player.setExperiencePoints(0);

                // Get droppable slots
                List<Integer> droppableSlots = getDroppableSlots(player);

                if (droppableSlots.isEmpty()) {
                        LOGGER.info("Combat quit penalty applied to '{}': XP lost, no droppable items",
                                player.getNameForScoreboard());
                        return;
                }

                // Calculate 50% (rounded up)
                int totalDroppable = droppableSlots.size();
                int itemsToDrop = (int) Math.ceil(totalDroppable * 0.5);

                // Randomly shuffle
                Collections.shuffle(droppableSlots);

                // Remove and drop items
                int dropped = 0;
                for (int slot : droppableSlots) {
                        if (dropped >= itemsToDrop) break;

                        ItemStack stack = player.getInventory().getStack(slot);
                        if (!stack.isEmpty()) {
                                spawnItemDrop(world, pos, stack.copy());
                                player.getInventory().setStack(slot, ItemStack.EMPTY);
                                dropped++;
                        }
                }

                LOGGER.info("Combat quit penalty applied to '{}': dropped {}/{} items, XP lost",
                        player.getNameForScoreboard(), dropped, totalDroppable);
        }

        // ========== Utility Methods ==========

        /**
         * Get inventory slot indices eligible for dropping.
         * Includes: main inventory (0-35), offhand (40)
         * Excludes: armor slots (36-39), shulker box items
         */
        private List<Integer> getDroppableSlots(ServerPlayerEntity player) {
                List<Integer> slots = new ArrayList<>();
                PlayerInventory inventory = player.getInventory();

                // Main inventory: slots 0-35
                for (int i = 0; i < 36; i++) {
                        ItemStack stack = inventory.getStack(i);
                        if (!stack.isEmpty() && !isShulkerBox(stack)) {
                                slots.add(i);
                        }
                }

                // Offhand: slot 40
                ItemStack offhand = inventory.getStack(40);
                if (!offhand.isEmpty() && !isShulkerBox(offhand)) {
                        slots.add(40);
                }

                // Armor slots 36-39 are NOT included (protected)
                return slots;
        }

        /**
         * Check if an ItemStack is a shulker box (any color).
         * Shulker boxes are protected from combat drops.
         */
        private boolean isShulkerBox(ItemStack stack) {
                if (stack.isEmpty()) return false;
                if (stack.getItem() instanceof BlockItem blockItem) {
                        return blockItem.getBlock() instanceof ShulkerBoxBlock;
                }
                return false;
        }

        /**
         * Spawn an item entity at a specific position in the world.
         * Items can be picked up immediately by any player.
         */
        private void spawnItemDrop(ServerWorld world, Vec3d pos, ItemStack stack) {
                ItemEntity itemEntity = new ItemEntity(world, pos.x, pos.y, pos.z, stack);
                itemEntity.setVelocity(
                        world.random.nextTriangular(0, 0.1),
                        world.random.nextTriangular(0.1, 0.05),
                        world.random.nextTriangular(0, 0.1)
                );
                itemEntity.resetPickupDelay();
                world.spawnEntity(itemEntity);
        }
}
