<div align="center">

# CombatKeep

[![Solidus Integrated](https://img.shields.io/badge/Solidus-Integrated-8B5CF6?style=flat-square)](https://github.com/mohammad-salah-qasiaa/solidus)
[![Platform](https://img.shields.io/badge/Platform-Fabric-blue.svg?style=flat-square)](https://fabricmc.net/)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.21%2B-8B5CF6.svg?style=flat-square)](https://www.minecraft.net/)
[![Server Side](https://img.shields.io/badge/Side-Server--Only-red.svg?style=flat-square)](https://fabricmc.net/)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg?style=flat-square)](LICENSE)

**Keep your items. Pay the price if you die fighting.**

A server-side Fabric mod that enables permanent KeepInventory while adding meaningful PvP consequences — players who die in combat lose 50% of their items, and 10% of their balance is automatically transferred to the killer via Solidus Core.

</div>

---

## How It Works

On most servers, KeepInventory either makes the game too easy or it's disabled entirely. **CombatKeep** finds the middle ground:

- You always keep your items on death — no more losing your gear to lava or a creeper
- But if you die **while fighting another player**, you drop 50% of your items on the spot
- **10% of your balance** is deducted and automatically sent to the player who killed you (requires Solidus Core)
- If you **log out to escape a fight** — you're treated as if you died in combat

Fair survival. Meaningful PvP consequences. Economy-driven combat.

---

## Features

### KeepInventory — Always On

- Automatically enforced on all worlds at startup — cannot be overridden, even by operators
- On normal death (outside combat): only XP is lost, all items are kept

### Combat Tag System

- Hitting another player activates a **15-second combat tag** on both players
- The timer **resets** every time either player lands a hit
- A **Boss Bar** appears at the top of the screen showing remaining combat time

| Time Remaining | Boss Bar Color |
|---|---|
| More than 7 seconds | Red |
| 7 seconds or less | Yellow |
| 3 seconds or less | White (blinking) |

### Combat Death Penalty

When a player dies while combat-tagged:

- Loses **all XP**
- Loses **50% of inventory items** (dropped at the death location, claimable by anyone)
- Items are selected **randomly** from the inventory
- **Protected from dropping:**
  - Currently worn armor (all armor slots)
  - Elytra
  - All Shulker Boxes (any color)

### Solidus Economy Integration

[![Solidus Integrated](https://img.shields.io/badge/Solidus-Integrated-8B5CF6?style=flat-square)](https://github.com/mohammad-salah-qasiaa/solidus)

When a player kills another player in combat, **10% of the victim's balance is automatically deducted and transferred to the killer** via Solidus Core's API. This integration is fully optional — if Solidus is not installed, CombatKeep functions normally without economy penalties.

**How the transfer works:**
1. Victim dies in combat → system reads their balance via `SolidusAPI.getBalance()`
2. 10% is calculated (rounded down to 2 decimal places)
3. `SolidusAPI.transfer()` performs an atomic victim-to-killer transfer
4. Both players are notified with the exact amount
5. Transactions are logged as `DEATH_PENALTY` and `DEATH_REWARD` in Solidus

**Example:** Player with 1,000 coins dies in combat → 100 coins transferred to the killer.

> Uses reflection to access SolidusAPI — no compile-time dependency on Solidus. Zero impact if Solidus is not installed.

### Combat Logging Penalty

If a player disconnects while combat-tagged:
- Treated as a **combat surrender**
- Same penalty as dying in combat: all XP lost + 50% of items dropped at their last known position
- Armor, Elytra, and Shulker Boxes are exempt

---

## Requirements

| Dependency | Version |
|---|---|
| Minecraft Java Edition | 1.21+ (Internal 26.1.x) |
| Fabric Loader | >= 0.19.2 |
| Fabric API | 0.149.1+26.1.2 |
| Java | 25+ |
| **Solidus Core** | Optional — enables 10% balance transfer on kill |

> No client-side installation required. Players can join with a vanilla client.

---

## Installation

1. Make sure **Fabric Loader 0.19.2+** is installed on your server
2. Download **Fabric API** for 26.1.x from [Modrinth](https://modrinth.com/mod/fabric-api) or [CurseForge](https://www.curseforge.com/minecraft/mc-mods/fabric-api)
3. Place `combatkeep-1.0.0.jar` and Fabric API `.jar` into your server's `mods/` folder
4. *(Optional)* Install [Solidus Core](https://github.com/mohammad-salah-qasiaa/solidus) to enable economy integration
5. Restart the server — that's it!

---

## Combat Flow

```
Player A hits Player B
        |
Combat Tag activated on BOTH players (15 seconds)
        |
    +---------------------------+
    |  Either player hits:      |
    |  Timer resets to 15 sec   |
    +---------------------------+
        |
+------------------+-----------------------+
| Death in combat  | Timer expires safely  |
+------------------+-----------------------+
| All XP lost      | No penalty            |
| 50% items drop   | Combat Tag removed    |
| 10% balance →    |                       |
|   killer (Solidus)|                      |
+------------------+-----------------------+

+------------------------------------------+
| Player disconnects during combat tag     |
| Same penalty as dying in combat          |
| (items + XP, no balance penalty since    |
|  killer may be offline)                  |
+------------------------------------------+
```

---

## Building from Source

**Requirements:** JDK 25, Git

```bash
git clone https://github.com/mohammad-salah-qasiaa/CombatKeep.git
cd CombatKeep
./gradlew build
# Output: build/libs/combatkeep-1.0.0.jar
```

---

## License

This project is licensed under the **MIT License** — see the [LICENSE](LICENSE) file for details.

---

<div align="center">
Made with coffee by <b>MOHD_Gs</b> — Part of the <a href="https://github.com/mohammad-salah-qasiaa/solidus">Solidus</a> ecosystem
</div>
