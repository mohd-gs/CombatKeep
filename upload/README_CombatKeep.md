<div align="center">

# ⚔️ CombatKeep

[![Platform](https://img.shields.io/badge/Platform-Fabric-blue.svg)](https://fabricmc.net/)
[![Minecraft Version](https://img.shields.io/badge/Minecraft-26.1.2-green.svg)](https://www.minecraft.net/)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Version](https://img.shields.io/badge/Version-1.0.0-orange.svg)](https://github.com/YOUR_USERNAME/combatkeep/releases)
[![Server Side](https://img.shields.io/badge/Side-Server--Only-red.svg)](https://fabricmc.net/)

**Keep your items. Pay the price if you die fighting.**

A server-side Fabric mod for Minecraft 26.1.2 that enables permanent KeepInventory while adding a punishing combat system — players who die in combat lose half their inventory, and those who try to log out to escape will face the same fate.

</div>

---

## 🤔 What Does This Mod Do?

On most servers, KeepInventory either makes the game too easy or it's disabled entirely. **CombatKeep** finds the middle ground:

- ✅ You always keep your items on death — no more losing your gear to lava or a creeper
- ⚔️ But if you die **while fighting another player**, you drop 50% of your items on the spot
- 🚪 And if you **log out to escape a fight** — you're treated as if you died in combat

Fair survival. Meaningful PvP consequences.

---

## ✨ Features

### 🎒 KeepInventory — Always On
- Automatically enabled on all worlds at startup
- Cannot be overridden — even by server operators
- On normal death (outside combat): only XP is lost, all items are kept

### 🏷️ Combat Tag System
- Hitting another player activates a **15-second combat tag** on both players
- The timer **resets** every time either player lands a hit
- A **Boss Bar** appears at the top of the screen showing remaining combat time
- Boss Bar color changes based on urgency:

| Time Remaining | Color |
|---|---|
| More than 7 seconds | 🔴 Red |
| 7 seconds or less | 🟡 Yellow |
| 3 seconds or less | ⚪ White (blinking) |

### 💀 Death Penalty During Combat
When a player dies while combat-tagged:
- ❌ Loses **all XP**
- ❌ Loses **50% of inventory items** (dropped at the death location, claimable by anyone)
- Items are selected **randomly** from the inventory
- **Exempt from dropping:**
  - 🛡️ Currently worn armor (slots 36–39)
  - 📦 All Shulker Boxes (any color)

### 🚪 Combat Logging Penalty
If a player disconnects while combat-tagged:
- Treated as a **combat surrender**
- Same penalty as dying in combat applies:
  - All XP lost
  - 50% of items dropped at their last known position
  - Armor and Shulker Boxes are exempt

---

## 📋 Requirements

| Dependency | Version |
|---|---|
| Minecraft Java Edition | 26.1.2 |
| Fabric Loader | ≥ 0.19.2 |
| Fabric API | 0.149.1+26.1.2 |
| Java | 25+ |

> **No client-side installation required.** Players can join with a vanilla client.

---

## 📥 Installation

1. Make sure **Fabric Loader 0.19.2+** is installed on your server
2. Download **Fabric API** for 26.1.2 from [Modrinth](https://modrinth.com/mod/fabric-api) or [CurseForge](https://www.curseforge.com/minecraft/mc-mods/fabric-api)
3. Place `combatkeep-1.0.0.jar` and the Fabric API `.jar` into your server's `mods/` folder
4. Restart the server — that's it!

---

## 🔨 Building from Source

**Requirements:** JDK 25 · Git

```bash
# Clone the repository
git clone https://github.com/YOUR_USERNAME/combatkeep.git
cd combatkeep

# Build the mod
./gradlew build

# Output jar will be at:
# build/libs/combatkeep-1.0.0.jar
```

---

## 🧠 How It Works

### Combat Event Flow

```
Player A hits Player B
        ↓
Combat Tag activated on BOTH players (15 seconds)
        ↓
Boss Bar displayed to both players
        ↓
    ┌────────────────────────────────┐
    │   Either player lands a hit:   │
    │   → Timer resets to 15 sec     │
    └────────────────────────────────┘
        ↓
┌──────────────────┬───────────────────────┐
│  Death in combat │  Timer expires safely │
├──────────────────┼───────────────────────┤
│  All XP lost     │  No penalty           │
│  50% items drop  │  Combat Tag removed   │
└──────────────────┴───────────────────────┘

┌────────────────────────────────────────┐
│  Player disconnects during combat tag  │
│  → Same penalty as dying in combat     │
└────────────────────────────────────────┘
```

### Item Drop Selection Logic
1. Collect all droppable inventory slots (excluding worn armor and Shulker Boxes)
2. Shuffle the list randomly
3. Select 50% of slots (rounded up)
4. Drop selected items as entities at the death/logout location
5. Remaining items stay in the player's inventory

---

## 📁 Project Structure

```
combatkeep/
├── build.gradle                        # Gradle build config
├── settings.gradle                     # Gradle project settings
├── gradle.properties                   # Minecraft/Fabric version pins
├── gradlew / gradlew.bat               # Gradle wrapper scripts
├── LICENSE
└── src/main/
    ├── java/com/combatkeep/mod/
    │   ├── CombatKeepMod.java          # Main mod entry point
    │   └── CombatBossBarManager.java   # Boss Bar display manager
    └── resources/
        └── fabric.mod.json             # Fabric mod metadata
```

---

## ⚙️ Technical Notes

<details>
<summary>For developers — Minecraft 26.1.2 changes</summary>

Minecraft 26.1.2 introduces several breaking changes compared to older Fabric setups:

| Change | Old | New |
|---|---|---|
| Loom plugin ID | `fabric-loom` | `net.fabricmc.fabric-loom` |
| Dependency keyword | `modImplementation` | `implementation` |
| Jar task | `remapJar` | `jar` |
| Required Java | 21 | **25** |
| Obfuscation | Obfuscated (needs mappings) | **Unobfuscated** — no mappings needed |

Because the game is now unobfuscated, the `mappings` block in `dependencies` has been removed entirely.

</details>

---

## 🤝 Contributing

Found a bug or have a suggestion? Feel free to:
- 🐛 [Open an issue](https://github.com/YOUR_USERNAME/combatkeep/issues)
- 🔧 Submit a pull request

---

## 📜 License

This project is licensed under the **MIT License** — see the [LICENSE](LICENSE) file for details.

---

<div align="center">
Made with ☕ by <b>MOHD_Gs</b>
</div>
