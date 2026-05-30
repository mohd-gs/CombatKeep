<div align="center">

# CombatKeep

[![Solidus Integrated](https://img.shields.io/badge/Solidus-Integrated-8B5CF6?style=flat-square)](https://github.com/mohd-gs/solidus-core)
[![Platform](https://img.shields.io/badge/Platform-Fabric-blue.svg?style=flat-square)](https://fabricmc.net/)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.21%2B-8B5CF6.svg?style=flat-square)](https://www.minecraft.net/)
[![Version](https://img.shields.io/badge/Version-1.1.0-orange.svg?style=flat-square)](https://github.com/mohd-gs/CombatKeep/releases)
[![Server Side](https://img.shields.io/badge/Side-Server--Only-red.svg?style=flat-square)](https://fabricmc.net/)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg?style=flat-square)](LICENSE)

**Keep your items. Pay the price if you die fighting.**

A server-side Fabric mod that enables permanent KeepInventory with meaningful PvP consequences — configurable item drops, 10% balance transfer to the killer via Solidus Core, respawn immunity, and full combat statistics tracking.

</div>

---

## Features

### KeepInventory — Always On
- Automatically enforced on all worlds — cannot be overridden by operators
- Normal death (outside combat): only XP is lost

### Combat Tag System
- Hitting another player activates a **combat tag** (default: 15 seconds, configurable)
- Timer **resets** on each hit between the tagged players
- **Boss Bar** shows remaining time with color transitions:

| Time Remaining | Color |
|---|---|
| More than 7 seconds | Red |
| 7 seconds or less | Yellow |
| 3 seconds or less | White (blinking) |

### Combat Death Penalty
When a player dies while combat-tagged:
- Loses **all XP**
- Loses a **configurable percentage** of inventory items (default: 50%, dropped at death location)
- Items selected **randomly** from inventory
- **Protected from dropping:** Armor, Elytra, Shulker Boxes

### Respawn Immunity
After dying in combat, the player receives a **10-second immunity** period (configurable):
- Immunity **prevents combat tagging** — the player cannot enter a new fight
- Immunity does **NOT prevent damage** — the player can still be attacked and take damage
- Countdown shown on the action bar
- Expiry announced in chat

### Solidus Economy Integration

[![Solidus Integrated](https://img.shields.io/badge/Solidus-Integrated-8B5CF6?style=flat-square)](https://github.com/mohd-gs/solidus-core)

When a player kills another player in combat, **10% of the victim's balance is automatically deducted and transferred to the killer** via Solidus Core's API. This also applies when a player **combat-logs** (disconnects during a fight).

**Transfer details:**
- Percentage and minimum amount are configurable
- `SolidusAPI.transfer()` performs an atomic victim-to-killer transfer
- Both players are notified with the exact amount
- Transactions logged as `DEATH_PENALTY` / `DEATH_REWARD` in Solidus

**Example:** Player with 1,000 coins dies in combat → 100 coins transferred to the killer.

> Uses reflection to access SolidusAPI — no compile-time dependency. Zero impact if Solidus is not installed.

### Combat Statistics
Full combat stats tracked per player and persisted across restarts:
- **Kills / Deaths / K/D Ratio**
- **Current Kill Streak / Best Kill Streak**
- **Economy Gained / Lost** from combat (requires Solidus)

Stats are saved every 5 minutes and on server stop.

### Combat Logging Penalty
If a player disconnects while combat-tagged:
- Treated as a **combat surrender**
- Same item/XP penalty as dying in combat
- **Solidus economy penalty** also applies (10% balance transferred to opponent)

### Admin Commands
All commands require OP level 3+:

| Command | Description |
|---|---|
| `/combatkeep status <player>` | Show combat tag + immunity status |
| `/combatkeep stats [player]` | View combat statistics (kills, deaths, K/D, streaks, economy) |
| `/combatkeep immunity <player>` | Check respawn immunity status |
| `/combatkeep reload` | Reload configuration from disk |

---

## Configuration

Auto-generated on first run at `config/combatkeep/combatkeep.properties`:

| Property | Default | Range | Description |
|---|---|---|---|
| `combatTagDurationSeconds` | 15 | 5–60 | Combat tag duration in seconds |
| `combatDeathDropPercent` | 50 | 10–100 | Percentage of items to drop on combat death |
| `solidusDeathPenaltyPercent` | 10 | 0–50 | Percentage of victim balance to transfer to killer (0 = disabled) |
| `solidusMinPenalty` | 0 | 0+ | Minimum penalty — if percentage is below this and balance > 0, charge this instead |
| `respawnImmunitySeconds` | 10 | 0–30 | Respawn immunity duration (0 = disabled) |
| `solidusCombatLogPenalty` | true | true/false | Whether to apply Solidus economy penalty on combat-log |

> Changes require `/combatkeep reload` or a server restart to take effect.

---

## Requirements

| Dependency | Version |
|---|---|
| Minecraft Java Edition | 1.21+ (Internal 26.1.x) |
| Fabric Loader | >= 0.19.2 |
| Fabric API | 0.149.1+26.1.2 |
| Java | 25+ |
| **Solidus Core** | Optional — enables balance transfer on kill |

> No client-side installation required. Players can join with a vanilla client.

---

## Installation

1. Make sure **Fabric Loader 0.19.2+** is installed on your server
2. Download **Fabric API** for 26.1.x from [Modrinth](https://modrinth.com/mod/fabric-api) or [CurseForge](https://www.curseforge.com/minecraft/mc-mods/fabric-api)
3. Place `combatkeep-1.1.0.jar` and Fabric API `.jar` into your server's `mods/` folder
4. *(Optional)* Install [Solidus Core](https://github.com/mohd-gs/solidus-core) to enable economy integration
5. Restart the server — that's it!

---

## Combat Flow

```
Player A hits Player B
        |
Combat Tag activated on BOTH players (configurable duration)
        |
    +---------------------------+
    |  Either player hits:      |
    |  Timer resets             |
    +---------------------------+
        |
+------------------+-----------------------+-------------------+
| Death in combat  | Timer expires safely  | Player disconnects|
+------------------+-----------------------+-------------------+
| All XP lost      | No penalty            | All XP lost       |
| X% items drop    | Combat Tag removed    | X% items drop     |
| 10% balance →    |                       | 10% balance →     |
|   killer (Solidus)|                      |   opponent*       |
| Stats recorded   |                       | Stats recorded    |
+------------------+-----------------------+-------------------+

* Economy penalty on combat-log only if opponent is online and Solidus is enabled

After combat death:
    Respawn Immunity (10s) → cannot be combat-tagged, CAN take damage
```

---

## File Structure

```
config/combatkeep/
├── combatkeep.properties    ← Configuration (auto-generated)
└── stats.json               ← Combat statistics (auto-generated, persisted)

src/main/java/com/combatkeep/mod/
├── CombatKeepMod.java          # Main mod entry point
├── CombatKeepConfig.java       # Configuration manager
├── CombatKeepCommand.java      # Admin commands
├── CombatBossBarManager.java   # Boss Bar display
├── RespawnImmunityManager.java # Respawn immunity tracking
├── CombatStatsManager.java     # Stats persistence (Gson/JSON)
├── PlayerStats.java            # Stats data class
└── SolidusIntegration.java     # Solidus economy integration
```

---

## Building from Source

**Requirements:** JDK 25, Git

```bash
git clone https://github.com/mohd-gs/CombatKeep.git
cd CombatKeep
./gradlew build
# Output: build/libs/combatkeep-1.1.0.jar
```

---

## License

This project is licensed under the **MIT License** — see the [LICENSE](LICENSE) file for details.

---

<div align="center">
Made with coffee by <b>MOHD_Gs</b> — Part of the <a href="https://github.com/mohd-gs/solidus-core">Solidus</a> ecosystem
</div>
