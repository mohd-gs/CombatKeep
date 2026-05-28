package com.combatkeep.mod;

/**
 * Data class holding combat statistics for a single player.
 * Instances are managed by {@link CombatStatsManager} and persisted to JSON.
 */
public class PlayerStats {

	public String playerName;
	public int kills;
	public int deaths;
	public double economyGained;
	public double economyLost;
	public int currentStreak;
	public int bestStreak;

	public PlayerStats() {
		this.playerName = "Unknown";
		this.kills = 0;
		this.deaths = 0;
		this.economyGained = 0.0;
		this.economyLost = 0.0;
		this.currentStreak = 0;
		this.bestStreak = 0;
	}

	/**
	 * Kill/Death ratio. Returns kills alone if deaths is 0 to avoid division by zero.
	 */
	public double getKDRatio() {
		if (deaths == 0) return kills;
		return (double) kills / deaths;
	}

	public void addKill() {
		kills++;
		currentStreak++;
		if (currentStreak > bestStreak) {
			bestStreak = currentStreak;
		}
	}

	public void addDeath() {
		deaths++;
		currentStreak = 0;
	}

	public void addEconomyGained(double amount) {
		economyGained += amount;
	}

	public void addEconomyLost(double amount) {
		economyLost += amount;
	}
}
