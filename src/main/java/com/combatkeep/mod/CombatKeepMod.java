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
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.entity.EquipmentSlot;
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
import java.util.concurrent.ConcurrentHashMap;

/**
 * CombatKeep — A server-side Fabric mod for Minecraft 26.1.x
 *
 * Features:
 * - KeepInventory is automatically enforced
 * - On normal death: player loses only XP
 * - On combat death (dying while combat-tagged): player loses a configurable
 *   percentage of inventory items + all XP
 * - Armor, Elytra, and Shulker Boxes are exempt from dropping
 * - Disconnecting while combat-tagged triggers the same penalty as combat death
 * - Combat tag duration is configurable (default: 15 seconds)
 * - Respawn immunity prevents combat tagging for N seconds after respawn
 *   (damage is still applied — only the tag is blocked)
 * - Optional Solidus integration: 10% balance transfer from victim to killer
 * - Combat statistics tracking (kills, deaths, K/D, economy gained/lost, streaks)
 * - Admin commands: /combatkeep status|reload|stats|immunity
 */
public class CombatKeepMod implements ModInitializer {

        public static final String MOD_ID = "combatkeep";
        public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

        /** Default combat tag duration — actual value comes from config */
        public static final int DEFAULT_COMBAT_TAG_DURATION_SECONDS = 15;

        // ========== Combat Tracking ==========

        /** Players currently in combat: UUID -> combat end tick (game ticks) */
        private static final Map<UUID, Long> combatTaggedPlayers = new ConcurrentHashMap<>();

        /** Combat pairs: player UUID -> opponent UUID */
        private static final Map<UUID, UUID> combatPartners = new ConcurrentHashMap<>();

        // ========== Death Data Tracking ==========

        /** Data about players who died and need penalty processing on respawn */
        private static final Map<UUID, DeathData> pendingDeathPenalties = new ConcurrentHashMap<>();

        /** Boss bar manager */
        private static CombatBossBarManager bossBarManager;

        /** Stats save counter — saves every 5 minutes (6000 ticks) */
        private static long lastStatsSaveTick = 0;
        private static final long STATS_SAVE_INTERVAL_TICKS = 6000;

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
                LOGGER.info("CombatKeep mod initializing for Minecraft 26.1.x...");

                // Load configuration
                CombatKeepConfig.getInstance().load();

                // Load combat statistics
                CombatStatsManager.load();

                // Initialize boss bar manager
                bossBarManager = new CombatBossBarManager();

                // Register admin commands
                CombatKeepCommand.register();

                // Force keepInventory game rule
                ServerLifecycleEvents.SERVER_STARTED.register(this::forceKeepInventory);
                ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);

                // Register event callbacks
                registerAttackCallback();
                registerDeathCallback();
                registerRespawnCallback();
                registerDisconnectCallback();
                registerCombatTagChecker();
                registerServerStopCleanup();

                int tagDuration = CombatKeepConfig.getInstance().getCombatTagDurationSeconds();
                int dropPercent = CombatKeepConfig.getInstance().getCombatDeathDropPercent();
                double penaltyPercent = CombatKeepConfig.getInstance().getSolidusDeathPenaltyPercent();
                int immunitySeconds = CombatKeepConfig.getInstance().getRespawnImmunitySeconds();

                LOGGER.info("CombatKeep mod initialized!");
                LOGGER.info("  - KeepInventory: enforced");
                LOGGER.info("  - Combat tag: {} seconds", tagDuration);
                LOGGER.info("  - Combat death penalty: {}% inventory drop + all XP", dropPercent);
                LOGGER.info("  - Protected items: armor, elytra, shulker boxes");
                LOGGER.info("  - Combat quit penalty: same as combat death");
                LOGGER.info("  - Respawn immunity: {} seconds", immunitySeconds);
                LOGGER.info("  - Solidus economy integration: {}",
                        SolidusIntegration.isEnabled()
                                ? "ENABLED (" + penaltyPercent + "% balance penalty)"
                                : "disabled (Solidus not found)");
        }

        // ========== Server Tick ==========

        /**
         * Main tick handler — runs keepInventory enforcement, immunity ticks,
         * and periodic stats saves.
         */
        private void onServerTick(MinecraftServer server) {
                long tick = server.getTickCount();

                // Force keepInventory every 200 ticks (10 seconds)
                if (tick % 200 == 0) {
                        forceKeepInventory(server);
                }

                // Respawn immunity tick (cleanup + action bar)
                RespawnImmunityManager.tick(server);

                // Periodic stats save every 5 minutes
                if (lastStatsSaveTick == 0) lastStatsSaveTick = tick;
                if (tick - lastStatsSaveTick >= STATS_SAVE_INTERVAL_TICKS) {
                        CombatStatsManager.save();
                        lastStatsSaveTick = tick;
                }
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
         * Timer refreshes on each hit. Respawn immunity blocks the tag.
         */
        private void registerAttackCallback() {
                AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
                        if (world.isClientSide()) return InteractionResult.PASS;

                        if (entity instanceof ServerPlayer target) {
                                ServerPlayer attacker = (ServerPlayer) player;

                                // Skip creative/spectator mode players
                                if (attacker.isCreative() || attacker.isSpectator()) return InteractionResult.PASS;
                                if (target.isCreative() || target.isSpectator()) return InteractionResult.PASS;

                                // Respawn immunity: tag is blocked but damage still goes through
                                if (RespawnImmunityManager.isImmune(attacker)) {
                                        attacker.sendSystemMessage(
                                                Component.literal("You have respawn immunity — combat tag blocked")
                                                        .withStyle(ChatFormatting.AQUA)
                                        );
                                        return InteractionResult.PASS; // damage still applied
                                }
                                if (RespawnImmunityManager.isImmune(target)) {
                                        attacker.sendSystemMessage(
                                                Component.literal(target.getScoreboardName() + " has respawn immunity — combat tag blocked")
                                                        .withStyle(ChatFormatting.AQUA)
                                        );
                                        return InteractionResult.PASS; // damage still applied
                                }

                                tagPlayerForCombat(attacker, target);
                        }

                        return InteractionResult.PASS;
                });
        }

        /**
         * On player death:
         * - Combat death: IMMEDIATELY drop configured percentage of items on the ground.
         *   Apply Solidus economy penalty if available. Record combat stats.
         * - Normal death: will lose only XP on respawn.
         * Removes combat tag from BOTH players when one dies in combat.
         */
        private void registerDeathCallback() {
                ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
                        if (!(entity instanceof ServerPlayer player)) return;

                        boolean wasCombatTagged = isCombatTagged(player);
                        Vec3 deathPos = player.position();
                        ServerLevel deathWorld = (ServerLevel) player.level();
                        List<Integer> droppedSlots = new ArrayList<>();

                        if (wasCombatTagged) {
                                // Drop configured percentage of items on death — before respawn
                                droppedSlots = dropItemsOnDeath(player, deathPos, deathWorld);

                                LOGGER.info("Player '{}' died while combat-tagged — {} items dropped immediately",
                                        player.getScoreboardName(), droppedSlots.size());

                                // ── Find opponent and clean up combat state ──
                                UUID opponentUuid = combatPartners.get(player.getUUID());
                                if (opponentUuid != null) {
                                        MinecraftServer server = player.level().getServer();
                                        if (server != null) {
                                                ServerPlayer killer = server.getPlayerList().getPlayer(opponentUuid);
                                                if (killer != null) {
                                                        removeCombatTag(killer);
                                                        killer.sendSystemMessage(
                                                                Component.literal("Your opponent died in combat! You are safe now.")
                                                                        .withStyle(ChatFormatting.GREEN)
                                                        );

                                                        // ── Solidus economy penalty (async) ──
                                                        if (SolidusIntegration.isEnabled()) {
                                                                SolidusIntegration.applyDeathPenalty(player, killer, penaltyAmount -> {
                                                                        CombatStatsManager.recordKill(killer.getUUID(), killer.getScoreboardName(), penaltyAmount);
                                                                        CombatStatsManager.recordDeath(player.getUUID(), player.getScoreboardName(), penaltyAmount);
                                                                });
                                                        } else {
                                                                // No Solidus — record stats with 0 economy
                                                                CombatStatsManager.recordKill(killer.getUUID(), killer.getScoreboardName(), 0.0);
                                                                CombatStatsManager.recordDeath(player.getUUID(), player.getScoreboardName(), 0.0);
                                                        }
                                                } else {
                                                        // Killer is offline — still record victim's death
                                                        CombatStatsManager.recordDeath(player.getUUID(), player.getScoreboardName(), 0.0);
                                                }
                                        }
                                        combatPartners.remove(opponentUuid);
                                        combatTaggedPlayers.remove(opponentUuid);
                                        bossBarManager.removeByUuid(opponentUuid);
                                } else {
                                        // No opponent found — still record victim's death
                                        CombatStatsManager.recordDeath(player.getUUID(), player.getScoreboardName(), 0.0);
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
         * Immediately drop a configured percentage of droppable items at the death position.
         * Returns the list of inventory slots that were dropped.
         */
        private List<Integer> dropItemsOnDeath(ServerPlayer player, Vec3 deathPos, ServerLevel deathWorld) {
                List<Integer> droppableSlots = getDroppableSlots(player);
                List<Integer> droppedSlots = new ArrayList<>();

                if (droppableSlots.isEmpty()) return droppedSlots;

                int totalDroppable = droppableSlots.size();
                double dropFraction = CombatKeepConfig.getInstance().getCombatDeathDropPercent() / 100.0;
                int itemsToDrop = (int) Math.ceil(totalDroppable * dropFraction);

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
         * - Combat death: items were already dropped on death. Clear them from
         *   the respawned inventory (keepInventory gave them back) and remove XP.
         *   Grant respawn immunity.
         * - Normal death: just lose XP.
         */
        private void registerRespawnCallback() {
                ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
                        UUID uuid = newPlayer.getUUID();
                        DeathData deathData = pendingDeathPenalties.remove(uuid);

                        if (deathData == null) return;

                        if (deathData.wasCombatTagged) {
                                applyCombatRespawnPenalty(newPlayer, deathData.slotsDroppedOnDeath);

                                // Grant respawn immunity after combat death
                                int immunitySeconds = CombatKeepConfig.getInstance().getRespawnImmunitySeconds();
                                if (immunitySeconds > 0) {
                                        RespawnImmunityManager.grantImmunity(newPlayer, immunitySeconds);
                                }
                        } else {
                                applyNormalDeathPenalty(newPlayer);
                        }
                });
        }

        /**
         * On player disconnect:
         * - Clean up pending death penalties to prevent memory leaks
         * - If combat-tagged: apply combat quit penalty (items + XP + economy),
         *   notify opponent, record stats, clean up
         */
        private void registerDisconnectCallback() {
                ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
                        ServerPlayer player = handler.getPlayer();
                        UUID uuid = player.getUUID();

                        // Clean up pending death penalties to prevent memory leak
                        DeathData staleDeath = pendingDeathPenalties.remove(uuid);
                        if (staleDeath != null) {
                                if (staleDeath.wasCombatTagged) {
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

                        // Remove immunity on disconnect
                        RespawnImmunityManager.removeImmunity(uuid);

                        if (isCombatTagged(player)) {
                                LOGGER.info("Player '{}' disconnected while combat-tagged — applying quit penalty",
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

                                                // ── Apply Solidus economy penalty on combat log (async) ──
                                                if (SolidusIntegration.isEnabled() && CombatKeepConfig.getInstance().isSolidusCombatLogPenalty()) {
                                                        SolidusIntegration.applyCombatLogPenalty(player, opponent, penaltyAmount -> {
                                                                CombatStatsManager.recordKill(opponent.getUUID(), opponent.getScoreboardName(), penaltyAmount);
                                                                CombatStatsManager.recordDeath(player.getUUID(), player.getScoreboardName(), penaltyAmount);
                                                        });
                                                } else {
                                                        CombatStatsManager.recordKill(opponent.getUUID(), opponent.getScoreboardName(), 0.0);
                                                        CombatStatsManager.recordDeath(player.getUUID(), player.getScoreboardName(), 0.0);
                                                }
                                        } else {
                                                // Opponent offline — still record the combat logger's death
                                                CombatStatsManager.recordDeath(player.getUUID(), player.getScoreboardName(), 0.0);
                                        }
                                        combatPartners.remove(opponentUuid);
                                        combatTaggedPlayers.remove(opponentUuid);
                                        bossBarManager.removeByUuid(opponentUuid);
                                } else {
                                        // No opponent — still record death
                                        CombatStatsManager.recordDeath(player.getUUID(), player.getScoreboardName(), 0.0);
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
                        // Use a snapshot of entries to avoid ConcurrentModificationException
                        // if the map is modified during iteration (e.g., player disconnect)
                        for (var entry : new ArrayList<>(combatTaggedPlayers.entrySet())) {
                                UUID uuid = entry.getKey();
                                long endTick = entry.getValue();

                                ServerPlayer player = server.getPlayerList().getPlayer(uuid);

                                if (currentTick >= endTick) {
                                        combatTaggedPlayers.remove(uuid);
                                        combatPartners.remove(uuid);

                                        if (player != null) {
                                                bossBarManager.removePlayer(player);
                                                player.sendSystemMessage(
                                                        Component.literal("Combat Tag expired — you are safe!")
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

        /**
         * Clean up all in-memory tracking maps and save stats when the server stops.
         */
        private void registerServerStopCleanup() {
                ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
                        // Save stats before shutdown
                        CombatStatsManager.save();

                        int combatTags = combatTaggedPlayers.size();
                        int partners = combatPartners.size();
                        int pendingDeaths = pendingDeathPenalties.size();
                        int bossBars = bossBarManager.getActiveBossBarCount();

                        combatTaggedPlayers.clear();
                        combatPartners.clear();
                        pendingDeathPenalties.clear();
                        RespawnImmunityManager.clearAll();

                        for (UUID uuid : new ArrayList<>(bossBarManager.getAllUuids())) {
                                bossBarManager.removeByUuid(uuid);
                        }

                        LOGGER.info("Server stopped — cleaned up: {} combat tags, {} partners, {} pending deaths, {} boss bars",
                                combatTags, partners, pendingDeaths, bossBars);
                });
        }

        // ========== Combat Tag Management ==========

        /**
         * Tag both players for combat. Timer duration comes from config.
         * Blocked if either player has respawn immunity.
         */
        public static void tagPlayerForCombat(ServerPlayer attacker, ServerPlayer target) {
                MinecraftServer server = attacker.level().getServer();
                if (server == null) return;

                int durationSeconds = CombatKeepConfig.getInstance().getCombatTagDurationSeconds();
                long combatEndTick = server.getTickCount() + (durationSeconds * 20L);

                combatTaggedPlayers.put(attacker.getUUID(), combatEndTick);
                combatPartners.put(attacker.getUUID(), target.getUUID());

                combatTaggedPlayers.put(target.getUUID(), combatEndTick);
                combatPartners.put(target.getUUID(), attacker.getUUID());

                bossBarManager.addPlayer(attacker);
                bossBarManager.addPlayer(target);

                String attackerName = attacker.getScoreboardName();
                String targetName = target.getScoreboardName();

                attacker.sendSystemMessage(
                        Component.literal("Combat Tag vs " + targetName + " - " + durationSeconds + "s!")
                                .withStyle(ChatFormatting.RED, ChatFormatting.BOLD)
                );
                target.sendSystemMessage(
                        Component.literal("Combat Tag vs " + attackerName + " - " + durationSeconds + "s!")
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
         * Get the current combat tag duration in seconds (from config).
         */
        public static int getCombatTagDurationSeconds() {
                return CombatKeepConfig.getInstance().getCombatTagDurationSeconds();
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
         */
        private void applyCombatRespawnPenalty(ServerPlayer player, List<Integer> slotsDroppedOnDeath) {
                player.setExperienceLevels(0);
                player.setExperiencePoints(0);

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
                double dropFraction = CombatKeepConfig.getInstance().getCombatDeathDropPercent() / 100.0;
                int itemsToDrop = (int) Math.ceil(totalDroppable * dropFraction);

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

        /**
         * Get all inventory slots that are eligible for dropping on combat death.
         * Protected items (not droppable):
         * - Armor items (checked by Equippable component)
         * - Shulker boxes
         * - Elytra
         */
        private List<Integer> getDroppableSlots(ServerPlayer player) {
                List<Integer> slots = new ArrayList<>();
                Inventory inventory = player.getInventory();

                // Main inventory + hotbar: slots 0-35
                for (int i = 0; i < 36; i++) {
                        ItemStack stack = inventory.getItem(i);
                        if (!stack.isEmpty() && isDroppableItem(stack)) {
                                slots.add(i);
                        }
                }

                // Offhand: slot 40
                ItemStack offhand = inventory.getItem(40);
                if (!offhand.isEmpty() && isDroppableItem(offhand)) {
                        slots.add(40);
                }

                return slots;
        }

        /**
         * Check if an item is droppable (not protected) on combat death.
         * Protected: armor (via Equippable component), shulker boxes, elytra.
         */
        private boolean isDroppableItem(ItemStack stack) {
                if (stack.isEmpty()) return false;

                // Check Equippable component — protect armor slot items
                Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
                if (equippable != null) {
                        EquipmentSlot slot = equippable.slot();
                        if (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR) {
                                return false;
                        }
                }

                // Shulker boxes — always protected
                if (stack.getItem() instanceof BlockItem blockItem) {
                        if (blockItem.getBlock() instanceof ShulkerBoxBlock) return false;
                }

                return true;
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
