package com.combatkeep.mod;

import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

/**
 * Admin commands for CombatKeep.
 *
 * All commands require OP level 3+.
 *
 * <ul>
 *   <li>{@code /combatkeep status <player>} — combat tag + immunity status</li>
 *   <li>{@code /combatkeep reload}          — reload config from disk</li>
 *   <li>{@code /combatkeep stats [player]}  — view combat statistics</li>
 *   <li>{@code /combatkeep immunity <player>} — check respawn immunity</li>
 * </ul>
 */
public class CombatKeepCommand {

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(Commands.literal("combatkeep")
				.requires(source -> source.hasPermission(3))

				// /combatkeep status <player>
				.then(Commands.literal("status")
					.then(Commands.argument("player", EntityArgument.player())
						.executes(CombatKeepCommand::executeStatus)
					)
				)

				// /combatkeep reload
				.then(Commands.literal("reload")
					.executes(CombatKeepCommand::executeReload)
				)

				// /combatkeep stats [player]
				.then(Commands.literal("stats")
					.executes(CombatKeepCommand::executeOwnStats)
					.then(Commands.argument("player", EntityArgument.player())
						.executes(CombatKeepCommand::executePlayerStats)
					)
				)

				// /combatkeep immunity <player>
				.then(Commands.literal("immunity")
					.then(Commands.argument("player", EntityArgument.player())
						.executes(CombatKeepCommand::executeImmunity)
					)
				)
			);
		});
	}

	// ── /combatkeep status <player> ──

	private static int executeStatus(CommandContext<CommandSourceStack> context) {
		ServerPlayer target = EntityArgument.getPlayer(context, "player");
		CommandSourceStack source = context.getSource();

		boolean isTagged = CombatKeepMod.isCombatTagged(target);
		boolean isImmune = RespawnImmunityManager.isImmune(target);

		source.sendSuccess(() -> Component.literal("=== CombatKeep: " + target.getScoreboardName() + " ===")
			.withStyle(ChatFormatting.GOLD), false);

		if (isTagged) {
			int remaining = CombatKeepMod.getRemainingCombatTime(target);
			source.sendSuccess(() -> Component.literal("  Combat Tag: ACTIVE (" + remaining + "s remaining)")
				.withStyle(ChatFormatting.RED), false);
		} else {
			source.sendSuccess(() -> Component.literal("  Combat Tag: None")
				.withStyle(ChatFormatting.GREEN), false);
		}

		if (isImmune) {
			int remaining = RespawnImmunityManager.getRemainingImmunity(target);
			source.sendSuccess(() -> Component.literal("  Respawn Immunity: ACTIVE (" + remaining + "s remaining)")
				.withStyle(ChatFormatting.AQUA), false);
		} else {
			source.sendSuccess(() -> Component.literal("  Respawn Immunity: None")
				.withStyle(ChatFormatting.GRAY), false);
		}

		boolean solidus = SolidusIntegration.isEnabled();
		source.sendSuccess(() -> Component.literal("  Solidus: " + (solidus ? "Connected" : "Not installed"))
			.withStyle(solidus ? ChatFormatting.GREEN : ChatFormatting.GRAY), false);

		return 1;
	}

	// ── /combatkeep reload ──

	private static int executeReload(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		CombatKeepConfig.getInstance().load();
		source.sendSuccess(() -> Component.literal("CombatKeep configuration reloaded!")
			.withStyle(ChatFormatting.GREEN), false);
		return 1;
	}

	// ── /combatkeep stats [player] ──

	private static int executeOwnStats(CommandContext<CommandSourceStack> context) {
		ServerPlayer player = context.getSource().getPlayerOrException();
		return showStats(context.getSource(), player);
	}

	private static int executePlayerStats(CommandContext<CommandSourceStack> context) {
		ServerPlayer target = EntityArgument.getPlayer(context, "player");
		return showStats(context.getSource(), target);
	}

	private static int showStats(CommandSourceStack source, ServerPlayer target) {
		PlayerStats stats = CombatStatsManager.getStats(target.getUUID());

		source.sendSuccess(() -> Component.literal("=== Combat Stats: " + target.getScoreboardName() + " ===")
			.withStyle(ChatFormatting.GOLD), false);
		source.sendSuccess(() -> Component.literal("  Kills: " + stats.kills)
			.withStyle(ChatFormatting.GREEN), false);
		source.sendSuccess(() -> Component.literal("  Deaths: " + stats.deaths)
			.withStyle(ChatFormatting.RED), false);
		source.sendSuccess(() -> Component.literal("  K/D Ratio: " + String.format("%.2f", stats.getKDRatio()))
			.withStyle(ChatFormatting.YELLOW), false);
		source.sendSuccess(() -> Component.literal("  Current Streak: " + stats.currentStreak)
			.withStyle(ChatFormatting.AQUA), false);
		source.sendSuccess(() -> Component.literal("  Best Streak: " + stats.bestStreak)
			.withStyle(ChatFormatting.LIGHT_PURPLE), false);

		if (SolidusIntegration.isEnabled()) {
			source.sendSuccess(() -> Component.literal("  Economy Gained: " + String.format("%.2f", stats.economyGained))
				.withStyle(ChatFormatting.GREEN), false);
			source.sendSuccess(() -> Component.literal("  Economy Lost: " + String.format("%.2f", stats.economyLost))
				.withStyle(ChatFormatting.RED), false);
		}

		return 1;
	}

	// ── /combatkeep immunity <player> ──

	private static int executeImmunity(CommandContext<CommandSourceStack> context) {
		ServerPlayer target = EntityArgument.getPlayer(context, "player");
		CommandSourceStack source = context.getSource();

		boolean isImmune = RespawnImmunityManager.isImmune(target);

		if (isImmune) {
			int remaining = RespawnImmunityManager.getRemainingImmunity(target);
			source.sendSuccess(() -> Component.literal(target.getScoreboardName() + " has respawn immunity: " + remaining + "s remaining")
				.withStyle(ChatFormatting.AQUA), false);
		} else {
			source.sendSuccess(() -> Component.literal(target.getScoreboardName() + " is not immune")
				.withStyle(ChatFormatting.GRAY), false);
		}

		return 1;
	}
}
