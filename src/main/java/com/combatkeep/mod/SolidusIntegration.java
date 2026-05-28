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
import java.util.function.Consumer;

/**
 * Optional integration with the Solidus economy mod via SolidusAPI.
 *
 * If Solidus is installed on the same server, CombatKeep will automatically
 * deduct a configurable percentage of the victim's balance on combat death
 * and transfer it to the killer. Also applies economy penalty on combat-log.
 *
 * All percentages and minimums are read from {@link CombatKeepConfig}.
 * Transactions are logged as DEATH_PENALTY / DEATH_REWARD in the Solidus
 * transaction log.
 *
 * If Solidus is NOT installed, this class does nothing — zero impact.
 *
 * Uses reflection to access SolidusAPI so there is no compile-time dependency.
 *
 * IMPORTANT: Because SolidusAPI.getBalance() and transfer() are async
 * (CompletableFuture), the penalty amount is not available synchronously.
 * Callers must pass a {@link Consumer} callback to receive the actual
 * transferred amount after the operation completes.
 */
public class SolidusIntegration {

	private static final Logger LOGGER = LoggerFactory.getLogger("combatkeep/solidus");

	/** Whether the Solidus mod is loaded at runtime */
	private static final boolean MOD_LOADED =
		FabricLoader.getInstance().isModLoaded("solidus");

	/** Cached SolidusAPI instance (resolved lazily on first use) */
	private static Object apiInstance = null;
	private static boolean apiResolved = false;

	public static boolean isEnabled() {
		if (!MOD_LOADED) return false;
		resolveApi();
		return apiInstance != null && isAvailable();
	}

	// ========== API Resolution ==========

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
	 * Apply the combat death economy penalty: deduct a configured percentage
	 * of the victim's balance and transfer it to the killer atomically.
	 *
	 * Because the balance lookup and transfer are async operations, the actual
	 * penalty amount is delivered via the {@code onAmountTransferred} callback
	 * (called on the server thread). If Solidus is not available or the
	 * victim has no balance, the callback receives 0.0.
	 *
	 * @param victim              The player who died in combat
	 * @param killer              The player who killed them
	 * @param onAmountTransferred Callback receiving the actual amount transferred
	 */
	public static void applyDeathPenalty(ServerPlayer victim, ServerPlayer killer,
					     Consumer<Double> onAmountTransferred) {
		if (!MOD_LOADED) { onAmountTransferred.accept(0.0); return; }
		resolveApi();
		if (apiInstance == null) { onAmountTransferred.accept(0.0); return; }

		double penaltyPercent = CombatKeepConfig.getInstance().getSolidusDeathPenaltyPercent() / 100.0;
		if (penaltyPercent <= 0) { onAmountTransferred.accept(0.0); return; }

		MinecraftServer server = victim.level().getServer();
		if (server == null) { onAmountTransferred.accept(0.0); return; }

		try {
			Class<?> apiClass = Class.forName("com.solidus.api.SolidusAPI");
			Method getBalance = apiClass.getMethod("getBalance", ServerPlayer.class);
			Method transfer = apiClass.getMethod("transfer", ServerPlayer.class, ServerPlayer.class, double.class);

			@SuppressWarnings("unchecked")
			CompletableFuture<Double> balanceFuture =
				(CompletableFuture<Double>) getBalance.invoke(apiInstance, victim);

			balanceFuture.thenAccept(balance -> {
				double penalty = calculatePenalty(balance, penaltyPercent);

				if (penalty <= 0) {
					LOGGER.debug("Victim '{}' has zero balance — no economy penalty to apply",
						victim.getScoreboardName());
					server.execute(() -> onAmountTransferred.accept(0.0));
					return;
				}

				try {
					@SuppressWarnings("unchecked")
					CompletableFuture<?> transferFuture =
						(CompletableFuture<?>) transfer.invoke(apiInstance, victim, killer, penalty);

					transferFuture.thenAccept(result -> {
						server.execute(() -> {
							victim.sendSystemMessage(
								Component.literal("You lost " + (long) (penaltyPercent * 100) + "% of your balance in combat: -" + String.format("%.2f", penalty))
									.withStyle(ChatFormatting.RED)
							);
							killer.sendSystemMessage(
								Component.literal("Earned " + String.format("%.2f", penalty) + " from killing " + victim.getScoreboardName())
									.withStyle(ChatFormatting.GOLD)
							);

							// Deliver the actual amount on the server thread
							onAmountTransferred.accept(penalty);
						});

						logTransactions(victim, killer, penalty, server,
							"DEATH_PENALTY", "DEATH_REWARD",
							"Combat death penalty", "Combat kill reward");
					}).exceptionally(ex -> {
						LOGGER.error("Failed to transfer balance via SolidusAPI.transfer()", ex);
						server.execute(() -> onAmountTransferred.accept(0.0));
						return null;
					});
				} catch (Exception e) {
					LOGGER.error("Failed to invoke SolidusAPI.transfer()", e);
					server.execute(() -> onAmountTransferred.accept(0.0));
				}
			}).exceptionally(ex -> {
				LOGGER.error("Failed to get victim balance from SolidusAPI.getBalance()", ex);
				server.execute(() -> onAmountTransferred.accept(0.0));
				return null;
			});

			LOGGER.info("Solidus combat death penalty initiated: victim={}, killer={}",
				victim.getScoreboardName(), killer.getScoreboardName());

		} catch (ClassNotFoundException e) {
			LOGGER.warn("SolidusAPI class not found — integration disabled");
			onAmountTransferred.accept(0.0);
		} catch (NoSuchMethodException e) {
			LOGGER.warn("SolidusAPI method not found — integration disabled");
			onAmountTransferred.accept(0.0);
		} catch (Exception e) {
			LOGGER.error("Unexpected error applying Solidus combat death penalty", e);
			onAmountTransferred.accept(0.0);
		}
	}

	// ========== Combat Log Penalty ==========

	/**
	 * Apply economy penalty when a player combat-logs: deduct a configured
	 * percentage of the combat-logger's balance and transfer to their opponent.
	 *
	 * @param combatLogger        The player who disconnected while combat-tagged
	 * @param opponent            The player who was left in combat
	 * @param onAmountTransferred Callback receiving the actual amount transferred
	 */
	public static void applyCombatLogPenalty(ServerPlayer combatLogger, ServerPlayer opponent,
						 Consumer<Double> onAmountTransferred) {
		if (!MOD_LOADED) { onAmountTransferred.accept(0.0); return; }
		resolveApi();
		if (apiInstance == null) { onAmountTransferred.accept(0.0); return; }

		double penaltyPercent = CombatKeepConfig.getInstance().getSolidusDeathPenaltyPercent() / 100.0;
		if (penaltyPercent <= 0) { onAmountTransferred.accept(0.0); return; }

		MinecraftServer server = combatLogger.level().getServer();
		if (server == null) { onAmountTransferred.accept(0.0); return; }

		try {
			Class<?> apiClass = Class.forName("com.solidus.api.SolidusAPI");
			Method getBalance = apiClass.getMethod("getBalance", ServerPlayer.class);
			Method transfer = apiClass.getMethod("transfer", ServerPlayer.class, ServerPlayer.class, double.class);

			@SuppressWarnings("unchecked")
			CompletableFuture<Double> balanceFuture =
				(CompletableFuture<Double>) getBalance.invoke(apiInstance, combatLogger);

			balanceFuture.thenAccept(balance -> {
				double penalty = calculatePenalty(balance, penaltyPercent);

				if (penalty <= 0) {
					LOGGER.debug("Combat-logger '{}' has zero balance — no economy penalty",
						combatLogger.getScoreboardName());
					server.execute(() -> onAmountTransferred.accept(0.0));
					return;
				}

				try {
					@SuppressWarnings("unchecked")
					CompletableFuture<?> transferFuture =
						(CompletableFuture<?>) transfer.invoke(apiInstance, combatLogger, opponent, penalty);

					transferFuture.thenAccept(result -> {
						server.execute(() -> {
							opponent.sendSystemMessage(
								Component.literal(combatLogger.getScoreboardName() + " combat-logged! You received " + String.format("%.2f", penalty))
									.withStyle(ChatFormatting.GOLD)
							);

							onAmountTransferred.accept(penalty);
						});

						logTransactions(combatLogger, opponent, penalty, server,
							"DEATH_PENALTY", "DEATH_REWARD",
							"Combat-log penalty", "Combat-log reward");
					}).exceptionally(ex -> {
						LOGGER.error("Failed to transfer combat-log penalty via SolidusAPI", ex);
						server.execute(() -> onAmountTransferred.accept(0.0));
						return null;
					});
				} catch (Exception e) {
					LOGGER.error("Failed to invoke SolidusAPI.transfer() for combat-log penalty", e);
					server.execute(() -> onAmountTransferred.accept(0.0));
				}
			}).exceptionally(ex -> {
				LOGGER.error("Failed to get combat-logger balance from SolidusAPI", ex);
				server.execute(() -> onAmountTransferred.accept(0.0));
				return null;
			});

			LOGGER.info("Solidus combat-log penalty initiated: logger={}, opponent={}",
				combatLogger.getScoreboardName(), opponent.getScoreboardName());

		} catch (Exception e) {
			LOGGER.error("Unexpected error applying Solidus combat-log penalty", e);
			onAmountTransferred.accept(0.0);
		}
	}

	// ========== Penalty Calculation ==========

	/**
	 * Calculate the penalty amount with minimum enforcement.
	 * If the percentage-based penalty is below the configured minimum and
	 * the balance is positive, the minimum is charged instead (capped at balance).
	 *
	 * @param balance        victim's current balance
	 * @param penaltyPercent penalty fraction (e.g. 0.10 for 10%)
	 * @return the penalty amount to charge
	 */
	private static double calculatePenalty(double balance, double penaltyPercent) {
		if (balance <= 0) return 0;

		double penalty = Math.floor(balance * penaltyPercent * 100) / 100;

		// Apply minimum penalty if configured
		double minPenalty = CombatKeepConfig.getInstance().getSolidusMinPenalty();
		if (penalty < minPenalty && balance > 0) {
			penalty = Math.min(minPenalty, balance);
			penalty = Math.floor(penalty * 100) / 100; // round down to 2 decimal places
		}

		return penalty;
	}

	// ========== Transaction Logging ==========

	/**
	 * Log penalty and reward transactions via Solidus TransactionLog.
	 * Non-critical — errors are logged at DEBUG level.
	 */
	private static void logTransactions(ServerPlayer from, ServerPlayer to, double amount,
					    MinecraftServer server, String penaltyType, String rewardType,
					    String penaltyDesc, String rewardDesc) {
		try {
			Class<?> apiClass = Class.forName("com.solidus.api.SolidusAPI");
			Method getTransactionLog = apiClass.getMethod("getTransactionLog");
			Object transactionLog = getTransactionLog.invoke(apiInstance);

			if (transactionLog == null) {
				LOGGER.debug("Solidus TransactionLog is null — skipping transaction logging");
				return;
			}

			@SuppressWarnings("unchecked")
			Class<Enum> typeClass = (Class<Enum>) Class.forName("com.solidus.economy.TransactionLog$Type");

			Enum penaltyEnum;
			Enum rewardEnum;
			try {
				penaltyEnum = Enum.valueOf(typeClass, penaltyType);
			} catch (IllegalArgumentException e) {
				penaltyEnum = Enum.valueOf(typeClass, "DEATH_PENALTY");
			}
			try {
				rewardEnum = Enum.valueOf(typeClass, rewardType);
			} catch (IllegalArgumentException e) {
				rewardEnum = Enum.valueOf(typeClass, "DEATH_REWARD");
			}

			Method logMethod = findLogMethod(transactionLog.getClass(), typeClass);

			if (logMethod != null) {
				UUID fromUuid = from.getUUID();
				UUID toUuid = to.getUUID();
				String fromName = from.getScoreboardName();
				String toName = to.getScoreboardName();

				invokeLog(logMethod, transactionLog, penaltyEnum, fromUuid, amount,
					penaltyDesc + " — lost to " + toName);
				invokeLog(logMethod, transactionLog, rewardEnum, toUuid, amount,
					rewardDesc + " — earned from " + fromName);

				LOGGER.debug("Solidus transactions logged: {} + {} for {} → {}",
					penaltyType, rewardType, fromName, toName);
			}
		} catch (ClassNotFoundException e) {
			LOGGER.debug("Solidus TransactionLog classes not found — skipping transaction logging");
		} catch (Exception e) {
			LOGGER.debug("Could not log Solidus transactions — non-critical", e);
		}
	}

	private static Method findLogMethod(Class<?> logClass, Class<?> typeClass) {
		try { return logClass.getMethod("log", typeClass, UUID.class, double.class, String.class); }
		catch (NoSuchMethodException ignored) {}
		try { return logClass.getMethod("log", typeClass, UUID.class, double.class); }
		catch (NoSuchMethodException ignored) {}
		try { return logClass.getMethod("log", typeClass, ServerPlayer.class, double.class, String.class); }
		catch (NoSuchMethodException ignored) {}
		try { return logClass.getMethod("log", typeClass, ServerPlayer.class, double.class); }
		catch (NoSuchMethodException ignored) {}
		return null;
	}

	private static void invokeLog(Method logMethod, Object transactionLog, Enum type,
				      UUID playerUuid, double amount, String description) {
		try {
			Class<?>[] paramTypes = logMethod.getParameterTypes();
			if (paramTypes.length == 4) {
				if (paramTypes[1] == UUID.class) {
					logMethod.invoke(transactionLog, type, playerUuid, amount, description);
				}
			} else if (paramTypes.length == 3) {
				if (paramTypes[1] == UUID.class) {
					logMethod.invoke(transactionLog, type, playerUuid, amount);
				}
			}
		} catch (Exception e) {
			LOGGER.debug("Failed to invoke TransactionLog.log() — non-critical", e);
		}
	}
}
