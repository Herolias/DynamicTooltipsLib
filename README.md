# DynamicTooltipsLib

> A library mod for **Hytale** that enables dynamic, per-item tooltips.

**DynamicTooltipsLib** overcomes Hytale's static tooltip limitation by transparently creating virtual item definitions. This allows two items of the same type (e.g., two Iron Swords) to display completely different descriptions, based on their metadata, NBT, or external state.

---

## Table of Contents

1. [Features](#features)
2. [Integration](#integration)
3. [Usage](#usage)
4. [Advanced Topics](#advanced-topics)
5. [Architecture](#architecture)
6. [Performance](#performance)

---

## Features

- **Per-Item Unique Tooltips**: Display durability, enchantment stats, kill counters, or lore specific to an individual item instance.
- **Mod Compatibility**: Uses a **Priority System** so multiple mods can add lines to the same item without conflict.

- **Uniform Virtual IDs**: Uses per-instance virtual item IDs for **all** inventory sections (hotbar, utility, tools, armor, storage, etc.), with an inbound filter to translate IDs back for interaction packets.
- **High Performance**: Caches item states and packet diffs to minimize network traffic and CPU usage.

---

## Integration

### 1. Gradle Dependency
Add the library to your `build.gradle` as `compileOnly`. You do not need to shade/bundle it; the server will load it.

```gradle
dependencies {
    compileOnly files("libs/DynamicTooltipsLib-1.0.0.jar")
}
```

### 2. Manifest Declaration
Add it to your `manifest.json`.

**Hard Dependency** (Your mod requires it):
```json
{
  "Name": "MyMod",
  "Dependencies": {
    "org.herolias.DynamicTooltipsLib": "1.0.0"
  }
}
```

**Optional Dependency** (Your mod supports it but doesn't need it):
```json
{
  "Name": "MyMod",
  "OptionalDependencies": {
    "org.herolias.DynamicTooltipsLib": "*"
  }
}
```
*Tip: If using Optional, ensure you guard your API calls with `Class.forName` checks.*

---

## Usage


Implement a `TooltipProvider` to generate tooltips programmatically.

#### 1. Implement the Interface
```java
public class MyEnchantmentTooltipProvider implements TooltipProvider {

    @Override
    public String getProviderId() {
        return "my-mod:enchantments";
    }

    @Override
    public int getPriority() {
        return TooltipPriority.DEFAULT; // 100
    }

    @Override
    public TooltipData getTooltipData(String itemId, String metadata) {
        if (metadata == null || !metadata.contains("enchantments")) return null;

        // Parse your metadata...
        // ...

        return TooltipData.builder()
            .hashInput("sharpness:5") // REQUIRED: Unique hash for caching
            .addLine("<color is='#FFAA00'>Sharpness V</color>")
            .build();
    }
}
```

#### 2. Register the Provider
Register it during your plugin's setup.

```java
@Override
protected void setup() {
    DynamicTooltipsApi api = DynamicTooltipsApiProvider.get();
    if (api != null) {
        api.registerProvider(new MyEnchantmentTooltipProvider());
    }
}
```

---

## Advanced Topics

### Provider Priority
When multiple mods add tooltips to the same item, they are stacked based on priority (Lowest -> Highest).

| Priority | Value | Use Case |
| :--- | :--- | :--- |
| `FIRST` | 0 | Header information, top of list. |
| `DEFAULT` | 100 | Standard stats, enchantments. |
| `LAST` | 200 | Footer information, debug info. |

### Force Refreshing
If you change a config value (e.g., "Show Item IDs: true"), you need to force a refresh so players see the change immediately.

```java
DynamicTooltipsApiProvider.get().refreshAllPlayers();
```

---

## Architecture

DynamicTooltipsLib uses **Virtual Item IDs** uniformly across all inventory sections:

1.  **All Slots (Hotbar, Utility, Tools, Armor, Storage, etc.)**: The library transparently swaps the real Item ID (e.g., `Sword`) with a **Virtual ID** (e.g., `Sword__dtt_hash123`). Each virtual ID has its own unique translation key, enabling per-instance tooltips even for items of the same type.
2.  **Inbound Translation**: Because interaction packets reference item IDs, the library's inbound filter automatically translates any virtual IDs back to real IDs in `SyncInteractionChains` and `MouseInteraction` packets, ensuring gameplay is never affected.

**Memory Management**:
The system uses bounded LRU caches (Max 10,000 items) to ensure that generating millions of unique tooltips (e.g., timestamps) does not crash the server.

---

## Performance

- **Fast-Path Caching**: If an item's state (ID + Metadata) hasn't changed, the library returns the cached result instantly (0ms).
- **Packet Diffing**: Only sends updates when necessary.
- **Thread Safety**: All registries are thread-safe and non-blocking.

---

## License

MIT License. Free to use in any Hytale mod.
