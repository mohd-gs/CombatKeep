package com.combatkeep.mod;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Optional integration with the Solidus economy mod via SolidusAPI.
 *
 * If Solidus is installed on the same server, CombatKeep will automatically
 * deduct 15% of the victim's balance on combat death and transfer it to the killer.
 * Transactions are logged as DEATH_PENALTY and DEATH_REWARD in the Solidus transaction log.
 *
 * If Solidus is NOT installed, this class does nothing — zero impact.
 *
 * Uses reflection to access SolidusAPI so there is no compile-time dependency.
 * The Solidus mod provides a stable API at com.solidus.api.SolidusAPI.
 *
 * SolidusAPI methods used (all via reflection):
 *   - getInstance()          → SolidusAPI singleton (null if not loaded)
 *   - isAvailable()          → quick check if Solidus is ready
 *   - getBalance(ServerPlayer)          → online player balance
 *   - transfer(ServerPlayer, ServerPlayer, double) → atomic online transfer
 *   - getTransactionLog()    → TransactionLog for recording events
 *
 * TransactionLog.Type enum values:
 *   - DEATH_PENALTY  → victim's deduction
 *   - DEATH_REWARD   → killer's reward
 */
public class SolidusIntegration {

        private static final Logger LOGGER = LoggerFactory.getLogger("combatkeep/solidus");

        /** Whether the Solidus mod is loaded at runtime */
        private static final boolean MOD_LOADED =
                        FabricLoader.getInstance().isModLoaded("solidus");

        /** Percentage of victim's balance to deduct on combat death (0.15 = 15%) */
        private static final double PENALTY_PERCENT = 0.15;

        /** Cached SolidusAPI instance (resolved lazily on first use) */
        private static Object apiInstance = null;
        private static boolean apiResolved = false;

        public static boolean isEnabled() {
                if (!MOD_LOADED) return false;
                resolveApi();
                return apiInstance != null && isAvailable();
        }

        // ========== API Resolution ==========

        /**
         * Lazily resolve the SolidusAPI singleton via reflection.
         * This is called once and cached so we don't reflect on every tick.
         */
        private static void resolveApi() {
                if (apiResolved) return;
                apiResolved = true;
                try {
                        Class<?> apiClass = Class.forName("com.solidus.api.SolidusAPI");
                        apiInstance = apiClass.getMethod("getInstance").invoke(null);
                        if (apiInstance != null) {
                                LOGGER.info("SolidusAPI resolved successfully — economy integration enabled");
                        } else {
                                LOGGER.warn("SolidusAPI.getInstance() returned null — Solidus may not be fully initialized yet");
                        }
                } catch (ClassNotFoundException e) {
                        LOGGER.warn("Solidus mod loaded but SolidusAPI class not found — integration disabled. " +
                                        "Ensure Solidus provides com.solidus.api.SolidusAPI");
                } catch (NoSuchMethodException e) {
                        LOGGER.warn("SolidusAPI.getInstance() method not found — integration disabled");
                } catch (Exception e) {
                        LOGGER.error("Failed to resolve SolidusAPI instance", e);
                }
        }

        /**
         * Check if SolidusAPI reports itself as available and ready.
         */
        private static boolean isAvailable() {
                if (apiInstance == null) return false;
                try {
                        Class<?> apiClass = Class.forName("com.solidus.api.SolidusAPI");
                        return (Boolean) apiClass.getMethod("isAvailable").invoke(apiInstance);
                } catch (Exception e) {
                        LOGGER.error("Failed to check SolidusAPI.isAvailable()", e);
                        return false;
                }
        }

        // ========== Death Penalty ==========

        /**
         * Apply the combat death economy penalty: deduct 15% of the victim's
         * balance and transfer it to the killer atomically.
         *
         * Uses SolidusAPI.transfer() for an atomic victim→killer transfer,
         * then logs the transaction as DEATH_PENALTY / DEATH_REWARD.
         *
         * This method is safe to call even if Solidus is not installed —
         * it will simply return immediately.
         *
         * @param victim The player who died in combat
         * @param killer The player who killed them
         */
        public static void applyDeathPenalty(ServerPlayer victim, ServerPlayer killer) {
                if (!MOD_LOADED) return;
                resolveApi();
                if (apiInstance == null) return;

                MinecraftServer server = victim.level().getServer();
                if (server == null) return;

                try {
                        Class<?> apiClass = Class.forName("com.solidus.api.SolidusAPI");
                        Method getBalance = apiClass.getMethod("getBalance", ServerPlayer.class);
                        Method transfer = apiClass.getMethod("transfer", ServerPlayer.class, ServerPlayer.class, double.class);

                        // Step 1: Get victim's balance first to calculate 15%
                        @SuppressWarnings("unchecked")
                        CompletableFuture<Double> balanceFuture =
                                        (CompletableFuture<Double>) getBalance.invoke(apiInstance, victim);

                        balanceFuture.thenAccept(balance -> {
                                // Calculate penalty: 15% of balance, rounded down to 2 decimal places
                                double penalty = Math.floor(balance * PENALTY_PERCENT * 100) / 100;
                                if (penalty <= 0) {
                                        LOGGER.debug("Victim '{}' has zero balance — no economy penalty to apply",
                                                        victim.getScoreboardName());
                                        return;
                                }

                                try {
                                        // Step 2: Atomic transfer from victim to killer
                                        @SuppressWarnings("unchecked")
                                        CompletableFuture<?> transferFuture =
                                                        (CompletableFuture<?>) transfer.invoke(apiInstance, victim, killer, penalty);

                                        transferFuture.thenAccept(result -> {
                                                // Step 3: Notify both players on the server thread
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

                                                // Step 4: Log the transactions in Solidus
                                                logTransactions(victim, killer, penalty, server);
                                        }).exceptionally(ex -> {
                                                LOGGER.error("Failed to transfer balance via SolidusAPI.transfer()", ex);
                                                return null;
                                        });
                                } catch (Exception e) {
                                        LOGGER.error("Failed to invoke SolidusAPI.transfer()", e);
                                }
                        }).exceptionally(ex -> {
                                LOGGER.error("Failed to get victim balance from SolidusAPI.getBalance()", ex);
                                return null;
                        });

                        LOGGER.info("Solidus combat penalty initiated: victim={}, killer={}",
                                        victim.getScoreboardName(), killer.getScoreboardName());

                } catch (ClassNotFoundException e) {
                        LOGGER.warn("SolidusAPI class not found — integration disabled. " +
                                        "Ensure Solidus provides com.solidus.api.SolidusAPI");
                } catch (NoSuchMethodException e) {
                        LOGGER.warn("SolidusAPI method not found — integration disabled. " +
                                        "Required: getBalance(ServerPlayer), transfer(ServerPlayer, ServerPlayer, double)");
                } catch (Exception e) {
                        LOGGER.error("Unexpected error applying Solidus combat penalty", e);
                }
        }

        // ========== Transaction Logging ==========

        /**
         * Log DEATH_PENALTY and DEATH_REWARD transactions via Solidus TransactionLog.
         * Uses reflection to access TransactionLog.log(Type, UUID, double, String)
         * with the DEATH_PENALTY and DEATH_REWARD enum values.
         *
         * Transaction logging is non-critical — if it fails, the balance transfer
         * still went through. Errors are logged at DEBUG level to avoid noise.
         */
        private static void logTransactions(ServerPlayer victim, ServerPlayer killer, double penalty, MinecraftServer server) {
                try {
                        Class<?> apiClass = Class.forName("com.solidus.api.SolidusAPI");
                        Method getTransactionLog = apiClass.getMethod("getTransactionLog");
                        Object transactionLog = getTransactionLog.invoke(apiInstance);

                        if (transactionLog == null) {
                                LOGGER.debug("Solidus TransactionLog is null — skipping transaction logging");
                                return;
                        }

                        // Resolve TransactionLog.Type enum values
                        @SuppressWarnings("unchecked")
                        Class<Enum> typeClass = (Class<Enum>) Class.forName("com.solidus.economy.TransactionLog$Type");
                        Enum deathPenaltyType = Enum.valueOf(typeClass, "DEATH_PENALTY");
                        Enum deathRewardType = Enum.valueOf(typeClass, "DEATH_REWARD");

                        // Find the log method — try common signatures
                        Method logMethod = findLogMethod(transactionLog.getClass(), typeClass);

                        if (logMethod != null) {
                                UUID victimUuid = victim.getUUID();
                                UUID killerUuid = killer.getUUID();
                                String victimName = victim.getScoreboardName();
                                String killerName = killer.getScoreboardName();

                                // Log DEATH_PENALTY for victim
                                invokeLog(logMethod, transactionLog, deathPenaltyType, victimUuid, penalty,
                                                "Combat death penalty — lost to " + killerName);

                                // Log DEATH_REWARD for killer
                                invokeLog(logMethod, transactionLog, deathRewardType, killerUuid, penalty,
                                                "Combat kill reward — earned from " + victimName);

                                LOGGER.debug("Solidus transactions logged: DEATH_PENALTY + DEATH_REWARD for {} → {}",
                                                victimName, killerName);
                        } else {
                                LOGGER.debug("Could not find TransactionLog.log() method — skipping transaction logging");
                        }

                } catch (ClassNotFoundException e) {
                        LOGGER.debug("Solidus TransactionLog classes not found — skipping transaction logging");
                } catch (Exception e) {
                        LOGGER.debug("Could not log Solidus transactions — non-critical", e);
                }
        }

        /**
         * Find the log() method on TransactionLog by trying common parameter signatures.
         * This makes the integration resilient to minor API changes in Solidus.
         */
        private static Method findLogMethod(Class<?> logClass, Class<?> typeClass) {
                // Try: log(Type, UUID, double, String)
                try {
                        return logClass.getMethod("log", typeClass, UUID.class, double.class, String.class);
                } catch (NoSuchMethodException ignored) {}

                // Try: log(Type, UUID, double)
                try {
                        return logClass.getMethod("log", typeClass, UUID.class, double.class);
                } catch (NoSuchMethodException ignored) {}

                // Try: log(Type, ServerPlayer, double, String)
                try {
                        return logClass.getMethod("log", typeClass, ServerPlayer.class, double.class, String.class);
                } catch (NoSuchMethodException ignored) {}

                // Try: log(Type, ServerPlayer, double)
                try {
                        return logClass.getMethod("log", typeClass, ServerPlayer.class, double.class);
                } catch (NoSuchMethodException ignored) {}

                return null;
        }

        /**
         * Invoke the log method with the appropriate parameter types.
         * Handles both UUID-based and ServerPlayer-based signatures.
         */
        private static void invokeLog(Method logMethod, Object transactionLog, Enum type, UUID playerUuid, double amount, String description) {
                try {
                        Class<?>[] paramTypes = logMethod.getParameterTypes();
                        if (paramTypes.length == 4) {
                                // log(Type, UUID/ServerPlayer, double, String)
                                if (paramTypes[1] == UUID.class) {
                                        logMethod.invoke(transactionLog, type, playerUuid, amount, description);
                                } else {
                                        // ServerPlayer-based — skip, we don't have the player reference here
                                        LOGGER.debug("TransactionLog.log() requires ServerPlayer — cannot log offline");
                                }
                        } else if (paramTypes.length == 3) {
                                // log(Type, UUID/ServerPlayer, double)
                                if (paramTypes[1] == UUID.class) {
                                        logMethod.invoke(transactionLog, type, playerUuid, amount);
                                }
                        }
                } catch (Exception e) {
                        LOGGER.debug("Failed to invoke TransactionLog.log() — non-critical", e);
                }
        }
}
