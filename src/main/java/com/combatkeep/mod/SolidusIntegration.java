package com.combatkeep.mod;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;

/**
 * Optional integration with the Solidus economy mod.
 *
 * If Solidus is installed on the same server, CombatKeep will automatically
 * deduct 15% of the victim's balance on combat death and transfer it to the killer.
 * If Solidus is NOT installed, this class does nothing — zero impact.
 *
 * Uses reflection to access Solidus API so there is no compile-time dependency.
 * Setup: add "solidus": "*" to the "suggests" block in fabric.mod.json.
 */
public class SolidusIntegration {

        private static final Logger LOGGER = LoggerFactory.getLogger("combatkeep/solidus");

        /** Whether the Solidus mod is loaded at runtime */
        private static final boolean ENABLED =
                FabricLoader.getInstance().isModLoaded("solidus");

        /** Percentage of victim's balance to deduct on combat death (0.15 = 15%) */
        private static final double PENALTY_PERCENT = 0.15;

        public static boolean isEnabled() {
                return ENABLED;
        }

        /**
         * Apply the combat death economy penalty: deduct 15% of the victim's
         * balance and transfer it to the killer.
         *
         * This method is safe to call even if Solidus is not installed —
         * it will simply return immediately.
         *
         * @param victim The player who died in combat
         * @param killer The player who killed them
         */
        public static void applyDeathPenalty(ServerPlayer victim, ServerPlayer killer) {
                if (!ENABLED) return;

                MinecraftServer server = victim.level().getServer();

                try {
                        // Resolve Solidus API methods via reflection
                        Class<?> solidusModClass = Class.forName("com.solidus.SolidusMod");
                        Method getEconomyEngine = solidusModClass.getMethod("getEconomyEngine");
                        Object economyEngine = getEconomyEngine.invoke(null);

                        if (economyEngine == null) {
                                LOGGER.warn("Solidus EconomyEngine is null — skipping combat penalty");
                                return;
                        }

                        Method getBalanceManager = economyEngine.getClass().getMethod("getBalanceManager");
                        Object balanceManager = getBalanceManager.invoke(economyEngine);

                        if (balanceManager == null) {
                                LOGGER.warn("Solidus BalanceManager is null — skipping combat penalty");
                                return;
                        }

                        Method getBalance = balanceManager.getClass().getMethod("getBalance", ServerPlayer.class);
                        Method subtractBalance = balanceManager.getClass().getMethod("subtractBalance", ServerPlayer.class, double.class);
                        Method addBalance = balanceManager.getClass().getMethod("addBalance", ServerPlayer.class, double.class);

                        // Get victim's balance
                        @SuppressWarnings("unchecked")
                        CompletableFuture<Double> balanceFuture =
                                (CompletableFuture<Double>) getBalance.invoke(balanceManager, victim);

                        balanceFuture.thenAccept(balance -> {
                                double penalty = Math.floor(balance * PENALTY_PERCENT * 100) / 100;
                                if (penalty <= 0) return;

                                try {
                                        // Subtract from victim
                                        @SuppressWarnings("unchecked")
                                        CompletableFuture<Double> subtractFuture =
                                                (CompletableFuture<Double>) subtractBalance.invoke(balanceManager, victim, penalty);

                                        subtractFuture.thenAccept(newVictimBalance -> {
                                                try {
                                                        // Add to killer
                                                        @SuppressWarnings("unchecked")
                                                        CompletableFuture<Double> addFuture =
                                                                (CompletableFuture<Double>) addBalance.invoke(balanceManager, killer, penalty);

                                                        addFuture.thenAccept(newKillerBalance -> {
                                                                // Notify players on the server thread
                                                                server.execute(() -> {
                                                                        victim.sendSystemMessage(
                                                                                Component.literal("You lost 15% of your balance in combat: -" + String.format("%.2f", penalty))
                                                                                        .withStyle(ChatFormatting.RED)
                                                                        );
                                                                        killer.sendSystemMessage(
                                                                                Component.literal("Earned " + String.format("%.2f", penalty) + " from killing " + victim.getScoreboardName())
                                                                                        .withStyle(ChatFormatting.GOLD)
                                                                        );
                                                                });
                                                        });
                                                } catch (Exception e) {
                                                        LOGGER.error("Failed to add balance to killer via Solidus", e);
                                                }
                                        });
                                } catch (Exception e) {
                                        LOGGER.error("Failed to subtract balance from victim via Solidus", e);
                                }
                        }).exceptionally(ex -> {
                                LOGGER.error("Failed to get victim balance from Solidus", ex);
                                return null;
                        });

                        LOGGER.info("Solidus combat penalty initiated: victim={}, killer={}", victim.getScoreboardName(), killer.getScoreboardName());

                } catch (ClassNotFoundException e) {
                        LOGGER.warn("Solidus mod found but API class not found — integration disabled. " +
                                "Ensure Solidus exposes com.solidus.SolidusMod.getEconomyEngine()");
                } catch (NoSuchMethodException e) {
                        LOGGER.warn("Solidus API method not found — integration disabled. " +
                                "Ensure Solidus exposes getEconomyEngine(), getBalanceManager(), " +
                                "getBalance(), subtractBalance(), addBalance()");
                } catch (Exception e) {
                        LOGGER.error("Unexpected error applying Solidus combat penalty", e);
                }
        }
}
