# DynamicTooltipsLib

A library mod for **Hytale** that enables other mods to add dynamic, per-item tooltips to the inventory system.

Hytale resolves item descriptions per item *type* — two items of the same type always share the same tooltip. DynamicTooltipsLib solves this by transparently creating virtual item definitions and intercepting inventory packets, so each individual item can display unique tooltip content.

Mod developers get a simple, high-level API. The library handles all the complexity internally.

---

## Table of Contents

- [Features](#features)
- [Installation](#installation)
  - [Hard Dependency](#hard-dependency-mod-requires-the-lib-to-function)
  - [Optional Dependency (Bridge Pattern)](#optional-dependency-mod-works-without-the-lib)
- [Quick Start](#quick-start)
- [API Reference](#api-reference)
  - [DynamicTooltipsApi](#dynamictooltipsapi)
  - [TooltipProvider](#tooltipprovider)
  - [TooltipData](#tooltipdata)
  - [TooltipPriority](#tooltippriority)
  - [CustomTooltipKeys](#customtooltipkeys)
- [Guides](#guides)
  - [Adding Lines to a Tooltip](#adding-lines-to-a-tooltip)
  - [Overriding an Item's Name](#overriding-an-items-name)
  - [Using Metadata Keys (No Provider Needed)](#using-metadata-keys-no-provider-needed)
  - [Refreshing Tooltips After Config Changes](#refreshing-tooltips-after-config-changes)
  - [Multi-Mod Composition](#multi-mod-composition)
- [Architecture Overview](#architecture-overview)
- [Performance](#performance)
- [License](#license)

---

## Features

- **Per-item tooltips** — Two swords of the same type can show completely different tooltip text.
- **Multi-mod support** — Multiple mods can contribute to the same item's tooltip. Lines are composed by priority; no mod needs to know about any other.
- **Additive lines** — Append extra lines below the original description (e.g. enchantment stats, durability info).
- **Name & description overrides** — Replace an item's display name or entire description when needed.
- **Built-in metadata keys** — Write `dtt_name` or `dtt_lines` into any item's BSON metadata to get tooltips without implementing a provider.
- **Cache invalidation & force-refresh** — `invalidateAll()` and `refreshAllPlayers()` let mods push tooltip changes to clients immediately (e.g. after a config reload).
- **Two-level caching** — An item-state fast-path cache eliminates all provider calls for unchanged items. A composed-output cache deduplicates identical tooltip results.
- **Hybrid packet strategy** — Virtual IDs for display-only sections; translation overrides for interactive sections (hotbar, utility, tools) so interactions are never broken.

---

## Installation

### 1. Add the dependency

Add the DynamicTooltipsLib JAR as a `compileOnly` dependency so your mod compiles against the API but doesn't bundle it.

**build.gradle:**
```gradle
dependencies {
    compileOnly files("path/to/DynamicTooltipsLib-1.0.0.jar")
}
```

### 2. Declare the dependency in your manifest

Add DynamicTooltipsLib to your `manifest.json` so the server loads it before your mod. Dependencies use the `group:name` format.

#### Hard dependency (mod requires the lib to function)

```json
{
    "Name": "MyMod",
    "Dependencies": {
        "org.herolias:DynamicTooltipsLib": "1.0.0"
    }
}
```

The server will refuse to load your mod if DynamicTooltipsLib is missing.

#### Optional dependency (mod works without the lib)

If tooltips are a nice-to-have rather than essential, use `OptionalDependencies`. This ensures correct load order when the lib IS present, but lets your mod load normally when it's not.

```json
{
    "Name": "MyMod",
    "OptionalDependencies": {
        "org.herolias:DynamicTooltipsLib": "*"
    }
}
```

When using an optional dependency, you **must** isolate all DynamicTooltipsLib references behind a runtime check. The recommended pattern is a **bridge class**:

**Step 1 — Create a bridge class** that contains all lib references:

```java
// TooltipBridge.java — only loaded when lib is confirmed present
import org.herolias.tooltips.api.DynamicTooltipsApi;
import org.herolias.tooltips.api.DynamicTooltipsApiProvider;

public final class TooltipBridge {

    public static boolean register(MyDataManager manager) {
        DynamicTooltipsApi api = DynamicTooltipsApiProvider.get();
        if (api != null) {
            api.registerProvider(new MyTooltipProvider(manager));
            return true;
        }
        return false;
    }

    public static void refreshAll() {
        DynamicTooltipsApi api = DynamicTooltipsApiProvider.get();
        if (api != null) {
            api.refreshAllPlayers();
        }
    }
}
```

**Step 2 — Guard with `Class.forName` before touching the bridge:**

```java
// In your main plugin class — NO imports from DynamicTooltipsLib here
private boolean tooltipsEnabled = false;

@Override
protected void setup() {
    // ... your other setup code ...

    try {
        Class.forName("org.herolias.tooltips.api.DynamicTooltipsApiProvider");
        // Bridge class is only loaded NOW — after we know the lib exists
        tooltipsEnabled = TooltipBridge.register(myDataManager);
    } catch (ClassNotFoundException | NoClassDefFoundError e) {
        logger.warn("DynamicTooltipsLib not found — tooltips disabled.");
    }
}
```

**Why this works:** Your main plugin class never directly references any DynamicTooltipsLib class — those references only exist inside `TooltipBridge`. Java loads classes lazily, so `TooltipBridge` (and its DynamicTooltipsLib imports) are only loaded when `TooltipBridge.register(...)` executes, which is inside the `try` block after the lib's presence has been confirmed. If the lib is absent, `Class.forName` throws, the bridge is never touched, and your mod continues normally.

**Important:** Catch `NoClassDefFoundError` in addition to `ClassNotFoundException`. The former can occur if the lib is partially present or if the JVM resolves a class reference unexpectedly.

---

## Quick Start

**1. Implement `TooltipProvider`:**

```java
import org.herolias.tooltips.api.*;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class MyTooltipProvider implements TooltipProvider {

    @Nonnull
    @Override
    public String getProviderId() {
        return "my-mod:stats";
    }

    @Override
    public int getPriority() {
        return TooltipPriority.DEFAULT; // 100
    }

    @Nullable
    @Override
    public TooltipData getTooltipData(@Nonnull String itemId, @Nullable String metadata) {
        if (metadata == null || !metadata.contains("\"my_data\"")) {
            return null; // Nothing to contribute
        }

        return TooltipData.builder()
                .hashInput("my_data:v1")  // REQUIRED — must be stable & unique per state
                .addLine("<color is=\"#55FF55\">+10 Speed</color>")
                .build();
    }
}
```

**2. Register it in your plugin's `setup()`:**

```java
import org.herolias.tooltips.api.DynamicTooltipsApi;
import org.herolias.tooltips.api.DynamicTooltipsApiProvider;

@Override
protected void setup() {
    DynamicTooltipsApi api = DynamicTooltipsApiProvider.get();
    if (api != null) {
        api.registerProvider(new MyTooltipProvider());
    }
}
```

That's it. Every item that has `"my_data"` in its metadata will now show "+10 Speed" in its tooltip.

---

## API Reference

### DynamicTooltipsApi

The main entry point. Obtain via `DynamicTooltipsApiProvider.get()`.

| Method | Description |
|---|---|
| `registerProvider(TooltipProvider)` | Registers a tooltip provider. Replaces any existing provider with the same ID. |
| `unregisterProvider(String id)` | Removes a provider by ID. Returns `true` if found. |
| `invalidatePlayer(UUID)` | Clears all cached tooltip data for one player. The next inventory packet triggers a full re-composition. |
| `invalidateAll()` | Clears all global and per-player caches. |
| `refreshPlayer(UUID)` | Invalidates + immediately sends a refreshed inventory packet to the player. |
| `refreshAllPlayers()` | Invalidates all caches + refreshes every online player. |

### TooltipProvider

Interface that mods implement to contribute tooltip content.

```java
public interface TooltipProvider {
    String getProviderId();                              // Unique ID, e.g. "my-mod:enchantments"
    int getPriority();                                   // Rendering order (ascending)
    TooltipData getTooltipData(String itemId, String metadata); // Called per item per packet
}
```

**Contract:**
- `getTooltipData` is called from packet-processing threads. It must be **thread-safe** and **fast**.
- Return `null` if your mod has nothing to contribute for this item.
- The `metadata` parameter is the item's BSON metadata as a JSON string, or `null` if the item has no metadata.
- Use a quick `String.contains()` check before parsing BSON to avoid unnecessary work.

### TooltipData

Immutable data object carrying your tooltip contribution. Created via `TooltipData.builder()`.

| Builder Method | Type | Description |
|---|---|---|
| `.hashInput(String)` | **Required** | A deterministic string representing this tooltip's state. Two items with the same `hashInput` from all providers will share a cached tooltip. Example: `"sharpness:3,durability:2"`. |
| `.addLine(String)` | Additive | Appends a line after the original description. Supports Hytale markup (`<color>`, etc.). |
| `.addLines(List<String>)` | Additive | Appends multiple lines. |
| `.nameOverride(String)` | Destructive | Replaces the item's display name. Highest-priority provider wins. |
| `.descriptionOverride(String)` | Destructive | Replaces the entire tooltip description. All additive lines from all providers are discarded. Highest-priority provider wins. |

**Additive vs. Destructive:**
- *Additive* contributions (lines) coexist peacefully with other mods. All providers' lines are concatenated in priority order.
- *Destructive* contributions (name/description overrides) are competitive. If multiple providers set a name override, the one with the highest priority wins.

### TooltipPriority

Standard priority constants for `getPriority()`.

| Constant | Value | Use Case |
|---|---|---|
| `FIRST` | 0 | Lines appear closest to the original description |
| `EARLY` | 50 | Before default providers |
| `DEFAULT` | 100 | Standard — use unless you have a reason not to |
| `LATE` | 150 | After default providers |
| `LAST` | 200 | Lines appear at the very bottom |
| `OVERRIDE` | 999 | Guarantees highest priority for name/description overrides |

Lower values render first (closer to the top). For destructive overrides, higher values win.

### CustomTooltipKeys

Standard BSON metadata keys that the **built-in provider** reads automatically. No `TooltipProvider` implementation needed — just write these keys into any item's metadata.

| Key | BSON Type | Behavior |
|---|---|---|
| `dtt_name` | String | Sets a name override (priority `LAST`) |
| `dtt_lines` | Array of Strings | Adds lines to the tooltip (priority `LAST`) |

**Example metadata (JSON representation):**
```json
{
    "dtt_name": "<color is=\"#FF5555\">Flame Sword</color>",
    "dtt_lines": [
        "<color is=\"#FF5555\">Burns enemies on hit</color>",
        "<color is=\"#AAAAAA\">Fire Damage +5</color>"
    ]
}
```

These keys coexist with other providers. `dtt_lines` are additive — they appear alongside enchantment info, durability info, etc. `dtt_name` is a name override at priority `LAST` (200), so it beats `DEFAULT` (100) providers but loses to `OVERRIDE` (999).

---

## Guides

### Adding Lines to a Tooltip

The most common use case. Your provider reads item metadata and returns formatted lines.

```java
@Nullable
@Override
public TooltipData getTooltipData(@Nonnull String itemId, @Nullable String metadata) {
    if (metadata == null) return null;
    
    // Quick check before expensive BSON parsing
    if (!metadata.contains("\"durability\"")) return null;
    
    int durability = parseDurability(metadata);
    int maxDurability = getMaxDurability(itemId);
    
    String color = durability > maxDurability / 2 ? "#55FF55" : "#FF5555";
    
    return TooltipData.builder()
            .hashInput("dur:" + durability + "/" + maxDurability)
            .addLine("<color is=\"" + color + "\">Durability: " 
                     + durability + "/" + maxDurability + "</color>")
            .build();
}
```

### Overriding an Item's Name

Use `nameOverride` to change an item's display name. This is destructive — highest priority wins.

```java
return TooltipData.builder()
        .hashInput("name:Legendary Blade")
        .nameOverride("<color is=\"#FFAA00\">Legendary Blade</color>")
        .addLine("<color is=\"#AAAAAA\">A weapon of ancient power</color>")
        .build();
```

### Using Metadata Keys (No Provider Needed)

For simple cases, you can skip implementing a `TooltipProvider` entirely. Just write the standard keys into the item's BSON metadata using whatever item manipulation your mod already does:

```java
// In your mod's item logic (using Hytale's ItemStack API)
BsonDocument meta = getItemMetadata(itemStack);
meta.put("dtt_name", new BsonString("Custom Sword Name"));
meta.put("dtt_lines", new BsonArray(List.of(
    new BsonString("<color is=\"#55FFFF\">Icy Touch</color>"),
    new BsonString("<color is=\"#AAAAAA\">Slows enemies on hit</color>")
)));
setItemMetadata(itemStack, meta);
```

The library's built-in `CustomDataTooltipProvider` picks these up automatically.

### Refreshing Tooltips After Config Changes

If your mod has a config that affects tooltip text (e.g. damage multipliers, enabled/disabled features), call `refreshAllPlayers()` after saving the config:

```java
// After saving your config
configManager.saveConfig();

DynamicTooltipsApi api = DynamicTooltipsApiProvider.get();
if (api != null) {
    api.refreshAllPlayers();
}
```

This immediately clears all caches and pushes fresh tooltip data to every online player. Without this call, players would see stale tooltips until their next natural inventory packet.

For per-player changes (e.g. a player-specific setting), use `refreshPlayer(uuid)` instead to avoid unnecessary work for other players.

### Multi-Mod Composition

When multiple mods register providers, the library composes their outputs automatically:

```
┌─────────────────────────────────────────────┐
│  Adamantite Pickaxe           ← item name   │
│                                             │
│  A sturdy mining tool.        ← original    │
│                                             │
│  Enchantments:                ← Mod A       │
│  • Efficiency III  +30% speed    (pri 100)  │
│  • Fortune II      +20% drops               │
│                                             │
│  Durability: 847/1200         ← Mod B       │
│                                    (pri 150) │
│                                             │
│  Soulbound                    ← Mod C       │
│                                    (pri 200) │
└─────────────────────────────────────────────┘
```

Each mod's provider runs independently. The registry merges results by priority:
1. **Additive lines** — concatenated in ascending priority order.
2. **Name override** — highest priority wins. Others are silently discarded.
3. **Description override** — highest priority wins. All additive lines from all providers are discarded.

---

## Architecture Overview

```
                        ┌─────────────────────────┐
                        │   Mod A (Enchantments)  │
                        │   TooltipProvider        │
                        └────────────┬────────────┘
                                     │
    ┌────────────────────────────────┼────────────────────────────────┐
    │  DynamicTooltipsLib            │                                │
    │                                ▼                                │
    │  ┌─────────────────────────────────────────────────────────┐   │
    │  │  TooltipRegistry                                        │   │
    │  │  • Queries all providers for each item                  │   │
    │  │  • Two-level cache (item-state + composed)              │   │
    │  │  • Priority-based composition                           │   │
    │  └─────────────────────┬───────────────────────────────────┘   │
    │                        │                                        │
    │                        ▼                                        │
    │  ┌─────────────────────────────────────────────────────────┐   │
    │  │  TooltipPacketAdapter                                   │   │
    │  │  • Intercepts outbound inventory packets                │   │
    │  │  • Hybrid strategy: virtual IDs + translation overrides │   │
    │  │  • Translates virtual IDs back on inbound packets       │   │
    │  │  • Caches raw inventory for force-refresh               │   │
    │  └─────────────────────┬───────────────────────────────────┘   │
    │                        │                                        │
    │                        ▼                                        │
    │  ┌─────────────────────────────────────────────────────────┐   │
    │  │  VirtualItemRegistry                                    │   │
    │  │  • Generates deterministic virtual item IDs             │   │
    │  │  • Creates cloned ItemBase definitions                  │   │
    │  │  • Per-player tracking (sent items, slot mapping)       │   │
    │  │  • Description & name key resolution                    │   │
    │  └─────────────────────────────────────────────────────────┘   │
    └─────────────────────────────────────────────────────────────────┘
                                     │
                                     ▼
                              Hytale Client
                        (sees unique tooltips per item)
```

**Why virtual items?**
Hytale resolves tooltips by item *type*. All `Tool_Pickaxe_Adamantite` items share one description key. To give each instance a unique tooltip, the library creates virtual item types like `Tool_Pickaxe_Adamantite__dtt_a1b2c3d4` — lightweight clones that differ only in their translation keys.

**Why the hybrid strategy?**
Items in interactive sections (hotbar, utility, tools) participate in `SyncInteractionChains` packets. The client sends the item ID back to the server during interactions. If we swapped to a virtual ID, the server wouldn't recognize it. So for interactive sections, we keep the real item ID and override the *translation text* instead. For display-only sections (armor, storage, containers), virtual IDs work safely.

---

## Performance

The library is designed for minimal server overhead:

| Optimization | Impact |
|---|---|
| **Item-state fast-path cache** | If an item's `(itemId, metadata)` pair has been seen before, the cached result is returned instantly — zero provider calls, zero BSON parsing. This covers ~95% of packets since most items don't change between ticks. |
| **Composed-output cache** | Different items producing the same provider outputs share one `ComposedTooltip` object. |
| **Per-player sent-item tracking** | Virtual item definitions (`UpdateItems`) are only sent once per player. Subsequent packets reuse the already-sent definition. |
| **Diff-based translation sending** | `UpdateTranslations` packets only include entries that changed since the last send. |
| **Negative-result caching** | Items with no tooltip data from any provider are cached as "empty" — avoids re-querying providers for vanilla items. |
| **Quick string checks in providers** | Providers use `String.contains()` before BSON parsing to skip irrelevant items instantly. |
| **Bounded cache size** | The item-state cache is capped at 4096 entries to prevent unbounded memory growth on servers with many unique item states. |

---

## License

[License Name/Type]
