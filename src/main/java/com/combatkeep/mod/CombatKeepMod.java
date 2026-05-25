package com.combatkeep.mod;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.block.ShulkerBoxBlock;
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

        /** Combat tag duration in ticks (20 ticks per second) */
        public static final int COMBAT_TAG_DURATION_TICKS = COMBAT_TAG_DURATION_SECONDS * 20;

        // ========== Combat Tracking ==========

        /** Players currently in combat: UUID -> combat end tick (game ticks) */
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
         * Items are dropped IMMEDIATELY on death (not waiting for respawn)
         * so opponents can pick them up right away. On respawn, we only need
         * to remove those already-dropped items from the player's inventory
         * (since keepInventory gives them back) and clear XP.
         */
        private static class DeathData {
                final Vec3 deathPosition;
                final ServerLevel deathWorld;
                final boolean wasCombatTagged;
                /** Slots whose items were already dropped on death; must be cleared on respawn */
                final List<Integer> slotsDroppedOnDeath;

                DeathData(Vec3 pos, ServerLevel world, boolean combatTagged, List<Integer> slotsDropped) {
                        this.deathPosition = pos;
                        this.deathWorld = world;
                        this.wasCombatTagged = combatTagged;
                        this.slotsDroppedOnDeath = slotsDropped;
                }
        }

        @Override
        public void onInitialize() {
                LOGGER.info("CombatKeep mod initializing for Minecraft 26.1.2...");

                bossBarManager = new CombatBossBarManager();

                // Force keepInventory game rule
                ServerLifecycleEvents.SERVER_STARTED.register(this::forceKeepInventory);
                ServerTickEvents.END_SERVER_TICK.register(server -> {
                        if (server.getTickCount() % 200 == 0) {
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
                for (ServerLevel world : server.getAllLevels()) {
                        Boolean currentValue = world.getGameRules().get(GameRules.KEEP_INVENTORY);
                        if (!currentValue) {
                                world.getGameRules().set(GameRules.KEEP_INVENTORY, true, server);
                                LOGGER.info("Forced keepInventory=true in world: {}", world.dimension());
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
                        if (world.isClientSide()) return InteractionResult.PASS;

                        if (entity instanceof ServerPlayer target) {
                                ServerPlayer attacker = (ServerPlayer) player;

                                // Skip creative/spectator mode players
                                if (attacker.isCreative() || attacker.isSpectator()) return InteractionResult.PASS;
                                if (target.isCreative() || target.isSpectator()) return InteractionResult.PASS;

                                tagPlayerForCombat(attacker, target);
                        }

                        return InteractionResult.PASS;
                });
        }

        /**
         * On player death:
         * - Combat death: IMMEDIATELY drop 50% of items on the ground so opponents
         *   can pick them up right away (prevents exploit where player refuses to respawn).
         *   Store which slots were dropped so we can clear them from the respawned inventory.
         * - Normal death: will lose only XP on respawn
         * Also removes combat tag from BOTH players when one dies in combat.
         */
        private void registerDeathCallback() {
                ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
                        if (!(entity instanceof ServerPlayer player)) return;

                        boolean wasCombatTagged = isCombatTagged(player);
                        Vec3 deathPos = player.position();
                        ServerLevel deathWorld = (ServerLevel) player.level();
                        List<Integer> droppedSlots = new ArrayList<>();

                        if (wasCombatTagged) {
                                // IMMEDIATELY drop 50% of items on death — before respawn!
                                // This prevents the exploit of staying on the death screen.
                                droppedSlots = dropItemsOnDeath(player, deathPos, deathWorld);

                                LOGGER.info("Player '{}' died while combat-tagged — {} items dropped immediately",
                                        player.getScoreboardName(), droppedSlots.size());

                                // Remove combat tag from the OPPONENT too — the fight is over
                                UUID opponentUuid = combatPartners.get(player.getUUID());
                                if (opponentUuid != null) {
                                        MinecraftServer server = player.level().getServer();
                                        if (server != null) {
                                                ServerPlayer opponent = server.getPlayerList().getPlayer(opponentUuid);
                                                if (opponent != null) {
                                                        removeCombatTag(opponent);
                                                        opponent.sendSystemMessage(
                                                                Component.literal("Your opponent died in combat! You are safe now.")
                                                                        .withStyle(ChatFormatting.GREEN)
                                                        );
                                                }
                                        }
                                        combatPartners.remove(opponentUuid);
                                        combatTaggedPlayers.remove(opponentUuid);
                                        bossBarManager.removeByUuid(opponentUuid);
                                }

                                removeCombatTag(player);
                        } else {
                                LOGGER.info("Player '{}' died normally (XP only loss)", player.getScoreboardName());
                        }

                        // Store death data for respawn processing
                        pendingDeathPenalties.put(player.getUUID(), new DeathData(
                                deathPos,
                                deathWorld,
                                wasCombatTagged,
                                droppedSlots
                        ));
                });
        }

        /**
         * Immediately drop 50% of droppable items at the death position.
         * Returns the list of inventory slots that were dropped.
         */
        private List<Integer> dropItemsOnDeath(ServerPlayer player, Vec3 deathPos, ServerLevel deathWorld) {
                List<Integer> droppableSlots = getDroppableSlots(player);
                List<Integer> droppedSlots = new ArrayList<>();

                if (droppableSlots.isEmpty()) return droppedSlots;

                int totalDroppable = droppableSlots.size();
                int itemsToDrop = (int) Math.ceil(totalDroppable * 0.5);

                Collections.shuffle(droppableSlots);

                for (int i = 0; i < droppableSlots.size() && droppedSlots.size() < itemsToDrop; i++) {
                        int slot = droppableSlots.get(i);
                        ItemStack stack = player.getInventory().getItem(slot);
                        if (!stack.isEmpty()) {
                                spawnItemDrop(deathWorld, deathPos, stack.copy());
                                player.getInventory().setItem(slot, ItemStack.EMPTY);
                                droppedSlots.add(slot);
                        }
                }

                return droppedSlots;
        }

        /**
         * On player respawn: apply the appropriate death penalty.
         * - Combat death: items were already dropped on death. Now we just need to
         *   clear them from the respawned player's inventory (since keepInventory
         *   gave them back) and remove XP.
         * - Normal death: just lose XP.
         */
        private void registerRespawnCallback() {
                ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
                        UUID uuid = newPlayer.getUUID();
                        DeathData deathData = pendingDeathPenalties.remove(uuid);

                        if (deathData == null) return;

                        if (deathData.wasCombatTagged) {
                                // Items were already dropped on death — just clear them from
                                // the respawned inventory (keepInventory gave them back)
                                applyCombatRespawnPenalty(newPlayer, deathData.slotsDroppedOnDeath);
                        } else {
                                applyNormalDeathPenalty(newPlayer);
                        }
                });
        }

        /**
         * On player disconnect:
         * - If combat-tagged: apply combat quit penalty, notify opponent, clean up
         * - If disconnected after death (before respawn): items were already dropped
         *   on death, so just clear the already-dropped slots from inventory and XP
         * - Clean up any pending death penalties to prevent memory leaks
         */
        private void registerDisconnectCallback() {
                ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
                        ServerPlayer player = handler.getPlayer();
                        UUID uuid = player.getUUID();

                        // Clean up pending death penalties to prevent memory leak
                        DeathData staleDeath = pendingDeathPenalties.remove(uuid);
                        if (staleDeath != null) {
                                if (staleDeath.wasCombatTagged) {
                                        // Items were already dropped on death — just clear them
                                        // from inventory (keepInventory preserved them) and remove XP
                                        for (int slot : staleDeath.slotsDroppedOnDeath) {
                                                player.getInventory().setItem(slot, ItemStack.EMPTY);
                                        }
                                        player.setExperienceLevels(0);
                                        player.setExperiencePoints(0);
                                        LOGGER.info("Player '{}' disconnected before respawn after combat death — items already dropped, inventory cleaned",
                                                player.getScoreboardName());
                                } else {
                                        player.setExperienceLevels(0);
                                        player.setExperiencePoints(0);
                                        LOGGER.info("Player '{}' disconnected before respawn — XP penalty applied",
                                                player.getScoreboardName());
                                }
                        }

                        if (isCombatTagged(player)) {
                                LOGGER.info("Player '{}' disconnected while combat-tagged - applying quit penalty",
                                        player.getScoreboardName());

                                UUID opponentUuid = combatPartners.get(uuid);
                                if (opponentUuid != null) {
                                        ServerPlayer opponent = server.getPlayerList().getPlayer(opponentUuid);
                                        if (opponent != null) {
                                                opponent.sendSystemMessage(
                                                        Component.literal(player.getScoreboardName() + " combat-logged! Their items have been dropped.")
                                                                .withStyle(ChatFormatting.GOLD)
                                                );
                                                removeCombatTag(opponent);
                                        }
                                        combatPartners.remove(opponentUuid);
                                        combatTaggedPlayers.remove(opponentUuid);
                                        bossBarManager.removeByUuid(opponentUuid);
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
                        long currentTick = server.getTickCount();
                        Iterator<Map.Entry<UUID, Long>> iterator = combatTaggedPlayers.entrySet().iterator();

                        while (iterator.hasNext()) {
                                Map.Entry<UUID, Long> entry = iterator.next();
                                UUID uuid = entry.getKey();
                                long endTick = entry.getValue();

                                ServerPlayer player = server.getPlayerList().getPlayer(uuid);

                                if (currentTick >= endTick) {
                                        iterator.remove();
                                        combatPartners.remove(uuid);

                                        if (player != null) {
                                                bossBarManager.removePlayer(player);
                                                player.sendSystemMessage(
                                                        Component.literal("Combat Tag expired - you are safe!")
                                                                .withStyle(ChatFormatting.GREEN)
                                                );
                                        } else {
                                                bossBarManager.removeByUuid(uuid);
                                        }
                                } else if (player != null) {
                                        int remaining = (int) Math.ceil((endTick - currentTick) / 20.0);
                                        bossBarManager.updateBossBar(player, remaining);
                                }
                        }
                });
        }

        // ========== Combat Tag Management ==========

        /**
         * Tag both players for combat. Timer resets to 15 seconds on each call.
         */
        public static void tagPlayerForCombat(ServerPlayer attacker, ServerPlayer target) {
                MinecraftServer server = attacker.level().getServer();
                if (server == null) return;

                long combatEndTick = server.getTickCount() + COMBAT_TAG_DURATION_TICKS;

                combatTaggedPlayers.put(attacker.getUUID(), combatEndTick);
                combatPartners.put(attacker.getUUID(), target.getUUID());

                combatTaggedPlayers.put(target.getUUID(), combatEndTick);
                combatPartners.put(target.getUUID(), attacker.getUUID());

                bossBarManager.addPlayer(attacker);
                bossBarManager.addPlayer(target);

                String attackerName = attacker.getScoreboardName();
                String targetName = target.getScoreboardName();

                attacker.sendSystemMessage(
                        Component.literal("⚔ Combat Tag vs " + targetName + " - " + COMBAT_TAG_DURATION_SECONDS + "s!")
                                .withStyle(ChatFormatting.RED, ChatFormatting.BOLD)
                );
                target.sendSystemMessage(
                        Component.literal("⚔ Combat Tag vs " + attackerName + " - " + COMBAT_TAG_DURATION_SECONDS + "s!")
                                .withStyle(ChatFormatting.RED, ChatFormatting.BOLD)
                );
        }

        /**
         * Check if a player is currently combat-tagged.
         */
        public static boolean isCombatTagged(ServerPlayer player) {
                Long endTick = combatTaggedPlayers.get(player.getUUID());
                if (endTick == null) return false;
                MinecraftServer server = player.level().getServer();
                if (server == null) return false;
                return server.getTickCount() < endTick;
        }

        /**
         * Get remaining combat time in seconds.
         */
        public static int getRemainingCombatTime(ServerPlayer player) {
                Long endTick = combatTaggedPlayers.get(player.getUUID());
                if (endTick == null) return 0;
                MinecraftServer server = player.level().getServer();
                if (server == null) return 0;
                long remaining = endTick - server.getTickCount();
                return remaining > 0 ? (int) Math.ceil(remaining / 20.0) : 0;
        }

        /**
         * Remove combat tag from a player.
         */
        public static void removeCombatTag(ServerPlayer player) {
                combatTaggedPlayers.remove(player.getUUID());
                combatPartners.remove(player.getUUID());
                bossBarManager.removePlayer(player);
        }

        // ========== Penalty Systems ==========

        private void applyNormalDeathPenalty(ServerPlayer player) {
                player.setExperienceLevels(0);
                player.setExperiencePoints(0);

                player.sendSystemMessage(
                        Component.literal("You died! Lost all experience points.")
                                .withStyle(ChatFormatting.YELLOW)
                );

                LOGGER.info("Normal death penalty applied to '{}': XP lost", player.getScoreboardName());
        }

        /**
         * Apply combat respawn penalty: clear items that were already dropped on death
         * from the respawned player's inventory, and remove all XP.
         * Since keepInventory gives items back on respawn, we need to remove
         * the same slots that were already dropped on the ground at death time.
         */
        private void applyCombatRespawnPenalty(ServerPlayer player, List<Integer> slotsDroppedOnDeath) {
                player.setExperienceLevels(0);
                player.setExperiencePoints(0);

                // Remove the items that were already dropped on death from the
                // respawned player's inventory (keepInventory gave them back)
                int cleared = 0;
                for (int slot : slotsDroppedOnDeath) {
                        ItemStack stack = player.getInventory().getItem(slot);
                        if (!stack.isEmpty()) {
                                player.getInventory().setItem(slot, ItemStack.EMPTY);
                                cleared++;
                        }
                }

                if (cleared > 0) {
                        player.sendSystemMessage(
                                Component.literal("You died in combat! Lost " + cleared + " items and all XP!")
                                        .withStyle(ChatFormatting.RED, ChatFormatting.BOLD)
                        );
                } else {
                        player.sendSystemMessage(
                                Component.literal("You died in combat! Lost all XP!")
                                        .withStyle(ChatFormatting.RED, ChatFormatting.BOLD)
                        );
                }

                LOGGER.info("Combat respawn penalty applied to '{}': cleared {} items from inventory, XP lost",
                        player.getScoreboardName(), cleared);
        }

        private void applyCombatQuitPenalty(ServerPlayer player) {
                Vec3 pos = player.position();
                ServerLevel world = (ServerLevel) player.level();

                player.setExperienceLevels(0);
                player.setExperiencePoints(0);

                List<Integer> droppableSlots = getDroppableSlots(player);

                if (droppableSlots.isEmpty()) {
                        LOGGER.info("Combat quit penalty applied to '{}': XP lost, no droppable items",
                                player.getScoreboardName());
                        return;
                }

                int totalDroppable = droppableSlots.size();
                int itemsToDrop = (int) Math.ceil(totalDroppable * 0.5);

                Collections.shuffle(droppableSlots);

                int dropped = 0;
                for (int slot : droppableSlots) {
                        if (dropped >= itemsToDrop) break;

                        ItemStack stack = player.getInventory().getItem(slot);
                        if (!stack.isEmpty()) {
                                spawnItemDrop(world, pos, stack.copy());
                                player.getInventory().setItem(slot, ItemStack.EMPTY);
                                dropped++;
                        }
                }

                LOGGER.info("Combat quit penalty applied to '{}': dropped {}/{} items, XP lost",
                        player.getScoreboardName(), dropped, totalDroppable);
        }

        // ========== Utility Methods ==========

        private List<Integer> getDroppableSlots(ServerPlayer player) {
                List<Integer> slots = new ArrayList<>();
                Inventory inventory = player.getInventory();

                for (int i = 0; i < 36; i++) {
                        ItemStack stack = inventory.getItem(i);
                        if (!stack.isEmpty() && !isShulkerBox(stack)) {
                                slots.add(i);
                        }
                }

                ItemStack offhand = inventory.getItem(40);
                if (!offhand.isEmpty() && !isShulkerBox(offhand)) {
                        slots.add(40);
                }

                return slots;
        }

        private boolean isShulkerBox(ItemStack stack) {
                if (stack.isEmpty()) return false;
                if (stack.getItem() instanceof BlockItem blockItem) {
                        return blockItem.getBlock() instanceof ShulkerBoxBlock;
                }
                return false;
        }

        private void spawnItemDrop(ServerLevel world, Vec3 pos, ItemStack stack) {
                ItemEntity itemEntity = new ItemEntity(world, pos.x, pos.y, pos.z, stack);
                itemEntity.setDeltaMovement(
                        (world.getRandom().nextDouble() - 0.5) * 0.2,
                        world.getRandom().nextDouble() * 0.1 + 0.05,
                        (world.getRandom().nextDouble() - 0.5) * 0.2
                );
                itemEntity.setNoPickUpDelay();
                world.addFreshEntity(itemEntity);
        }
}
