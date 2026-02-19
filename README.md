# DynamicTooltipsLib

> A library mod for **Hytale** that enables dynamic, per-item tooltips.

**DynamicTooltipsLib** overcomes Hytale's static tooltip limitation by transparently creating virtual item definitions. This allows two items of the same type (e.g., two Iron Swords) to display completely different descriptions, based on their metadata, NBT, or external state.

---

Why you should use this library:
The lib manages mod compatibility for you. It uses a priority system so multiple mods can add lines to the same item without conflict.
I will add more features and update the library regularly.

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
- **Locale-Aware Tooltips**: Providers receive the player's language code, enabling per-player translated tooltip content without extra packet work.
- **Uniform Virtual IDs**: Uses per-instance virtual item IDs for **all** inventory sections (hotbar, utility, tools, armor, storage, etc.), with an inbound filter to translate IDs back for interaction packets.
- **High Performance**: Caches item states and packet diffs to minimize network traffic and CPU usage.

---

## Integration

See [TooltipExample](https://github.com/Herolias/TooltipExample) for an example implementation, including a `/morph` command that demonstrates visual overrides.

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

### 1. Implement `TooltipProvider`

Create a class that implements the `TooltipProvider` interface. This is where you define the logic for your tooltips.

```java
public class MyEnchantmentTooltipProvider implements TooltipProvider {

    @Override
    public String getProviderId() {
        return "my-mod:my-tooltips";
    }

    @Override
    public int getPriority() {
        return TooltipPriority.DEFAULT; // 100
    }

    @Override
    public TooltipData getTooltipData(String itemId, String metadata) {
        // Fallback for calls without locale context (e.g. entity updates)
        return getTooltipData(itemId, metadata, "en-US");
    }

    @Override
    public TooltipData getTooltipData(String itemId, String metadata, String locale) {
        if (metadata == null || !metadata.contains("my-metadata")) return null;

        // Parse your metadata...
        // Use 'locale' to resolve translated text (e.g. via I18nModule)
        String label = resolveTranslation("enchantment.sharpness", locale, "Sharpness");

        return TooltipData.builder()
            .hashInput("sharpness:5") // REQUIRED: Unique hash for caching
            .addLine("<color is=\"#FFAA00\">" + label + " V</color>")
            .build();
    }
}
```

> **Note:** The `locale` parameter (e.g. `"en-US"`, `"de-DE"`) is passed automatically by the library during inventory packet processing. Compositions are cached per-locale, so each player sees tooltips in their own language.

### 2. Register the Provider

Register your provider during your plugin's setup phase.

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

## API Reference

### TooltipData.Builder

The `TooltipData.builder()` provides a fluent API for constructing tooltip data.

#### Additive Methods
*   `addLine(String line)`: Adds a single line to the end of the tooltip. Supports Hytale's rich text formatting (e.g., `<color>`).
*   `addLines(List<String> lines)`: Adds multiple lines at once.

#### Override Methods (Destructive)
These methods replace existing item properties. If multiple providers set these, the one with the highest priority wins.

*   `nameOverride(String name)`: Replaces the item's display name with raw text.
*   `nameTranslationKey(String key)`: Replaces the item's display name with a translation key (e.g., `server.items.sword.name`). **Takes precedence over `nameOverride`.**
*   `descriptionOverride(String description)`: Replaces the **entire** description (original + additive lines) with raw text.
*   `descriptionTranslationKey(String key)`: Replaces the **entire** description with a translation key. **Takes precedence over `descriptionOverride`.**
*   `addLineOverride(String line)`: Adds a line to the description override. Useful if you want to build the override line-by-line. *Note: `descriptionOverride(String)` takes precedence.*
*   `visualOverrides(ItemVisualOverrides overrides)`: Applies client-side visual changes (model, texture, etc.).

#### State Methods
*   `hashInput(String input)`: **Required.** A deterministic string representing the item's state (e.g., `enchant:sharpness:5`). Used for caching virtual IDs.

### Locale-Aware Providers

By default, `getTooltipData(itemId, metadata)` is called without locale context. To produce translated tooltips, override the locale-aware variant:

```java
@Override
public TooltipData getTooltipData(String itemId, String metadata, String locale) {
    // 'locale' is the player's language code, e.g. "de-DE"
    // Build localized additive lines here
}
```

The library caches compositions per-locale automatically. The `locale` may be `null` in contexts where a player language is unavailable (e.g. entity updates for other players); in that case, fall back to `"en-US"`.

### Visual Overrides Reference
<p align="center" width="100%">
<video src="https://private-user-images.githubusercontent.com/61795333/550015815-91694572-038d-4cef-8438-abaa54efe1c5.mp4?jwt=eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.eyJpc3MiOiJnaXRodWIuY29tIiwiYXVkIjoicmF3LmdpdGh1YnVzZXJjb250ZW50LmNvbSIsImtleSI6ImtleTUiLCJleHAiOjE3NzExMTYwMTksIm5iZiI6MTc3MTExNTcxOSwicGF0aCI6Ii82MTc5NTMzMy81NTAwMTU4MTUtOTE2OTQ1NzItMDM4ZC00Y2VmLTg0MzgtYWJhYTU0ZWZlMWM1Lm1wND9YLUFtei1BbGdvcml0aG09QVdTNC1ITUFDLVNIQTI1NiZYLUFtei1DcmVkZW50aWFsPUFLSUFWQ09EWUxTQTUzUFFLNFpBJTJGMjAyNjAyMTUlMkZ1cy1lYXN0LTElMkZzMyUyRmF3czRfcmVxdWVzdCZYLUFtei1EYXRlPTIwMjYwMjE1VDAwMzUxOVomWC1BbXotRXhwaXJlcz0zMDAmWC1BbXotU2lnbmF0dXJlPWQxMmViZWNhYTZlNmRiNWY5YzBlZDVlYmI3Y2FmMzcyYTU0NmIwYjllMWI5MTZkZjFlMDg0NjM2MzE4NzRlZjQmWC1BbXotU2lnbmVkSGVhZGVycz1ob3N0In0.hlRZJqNFqjn1dhpVHwVuS518Jou4iNWskaeALIa3nn0" width="80%" controls></video>
</p>

[Example Implementation](https://github.com/Herolias/TooltipExample)

Use `ItemVisualOverrides.builder()` to construct client-side visual overrides. All fields are optional; only non-null values will override the original item's properties.

```java
return TooltipData.builder()
    .hashInput("my_custom_sword_state") // Unique hash is required!
    .addLineOverride("<color is=\"#FF0000\">Override Line 1</color>") // Replaces original description, destructive. Make sure to test for compatibility with other mods.
    .addLineOverride("<color is=\"#00FF00\">Override Line 2</color>")
    .visualOverrides(ItemVisualOverrides.builder()
    .model("models/custom_sword.blockymodel")
    .texture("textures/custom_sword.png")
        .trails(myTrailArray)               // Add trail effects
        .playerAnimationsId("two_handed")   // Override holding animation
        .usePlayerAnimations(true)
        .light(myColorLight)                // Add a glow
        .reticleIndex(3)                    // Change the crosshair
        .build())
    .build();
```

#### Supported Fields
The following fields map directly to `ItemBase` properties. Some of them are experimental and may not work in all cases. Icon, model, texture, and qualityIndex are fully tested and supported.

| Field | Type | Description |
| :--- | :--- | :--- |
| **Model & Appearance** | | |
| `model` | `String` | 3D model asset path |
| `texture` | `String` | Texture asset path |
| `scale` | `Float` | Model scale multiplier |
| `qualityIndex` | `Integer` | Item quality/rarity tier |
| `clipsGeometry` | `Boolean` | Whether the model clips through world geometry |
| `set` | `String` | Item set membership (visual grouping) |
| **UI & Icon** | | |
| `icon` | `String` | Inventory icon asset path |
| `iconProperties` | `AssetIconProperties` | Icon scale, translation, and rotation in UI |
| `reticleIndex` | `Integer` | Crosshair/reticle graphic index |
| `renderDeployablePreview` | `Boolean` | Show placement preview ghost |
| `displayEntityStatsHUD` | `int[]` | Which entity stats are displayed on the HUD |
| `categories` | `String[]` | Creative library category tabs |
| **Effects** | | |
| `light` | `ColorLight` | Light emission (color + radius) |
| `particles` | `ModelParticle[]` | Particle effects on the item model |
| `firstPersonParticles` | `ModelParticle[]` | Particles visible only in first-person |
| `trails` | `ModelTrail[]` | Trail effects on the item model |
| **Animation** | | |
| `animation` | `String` | Item animation asset path |
| `droppedItemAnimation` | `String` | Animation when item is on the ground |
| `playerAnimationsId` | `String` | Player holding/using animation set |
| `usePlayerAnimations` | `Boolean` | Force enable/disable player animations |
| `itemAppearanceConditions` | `Map<Integer, ItemAppearanceCondition[]>` | Conditional visual states (e.g. charge level) |
| `pullbackConfig` | `ItemPullbackConfiguration` | Visual pullback positions for bows/crossbows |
| **Sound** | | |
| `soundEventIndex` | `Integer` | Sound event index for interactions |
| `itemSoundSetIndex` | `Integer` | Sound set index for item sounds |
| **Dropped Item** | | |
| `itemEntity` | `ItemEntityConfig` | Particle system, color, and visibility for dropped items |
| **Durability** | | |
| `durability` | `Double` | Max durability shown in tooltip (visual only) |
| **Armor / Weapon / Tool (EXPERIMENTAL)** | | |
| `armor` | `ItemArmor` | Replace the entire armor config (slot, modifiers, resistances) |
| `weapon` | `ItemWeapon` | Replace the entire weapon config (stat modifiers, dual wield) |
| `tool` | `ItemTool` | Replace the entire tool config (specs, speed) |

#### Convenience Methods (EXPERIMENTAL)

For common use cases, the builder also provides convenience methods that create the protocol objects for you:

| Method | Effect |
| :--- | :--- |
| `.armorSlot(ItemArmorSlot)` | Set which slot label to display (Head, Chest, Hands, Legs) |
| `.armorBaseDamageResistance(double)` | Set the base damage resistance number |
| `.armorStatModifiers(Map)` | **Replace** all armor stat modifiers |
| `.armorDamageResistance(Map)` | **Replace** the damage resistance map |
| `.weaponStatModifiers(Map)` | **Replace** all weapon stat modifiers |
| `.toolSpeed(float)` | Set the tool speed value |

> **Note:** The convenience methods above (`armorSlot`, `armorStatModifiers`, etc.) are **destructive** — they create a new armor/weapon/tool object that replaces the original item's data.

#### Additive Stat Modifiers

To **add** stat modifiers on top of the original item's existing ones (preserving them), use the additive API:

| Method | Effect |
| :--- | :--- |
| `.addArmorStatModifier(int statIndex, Modifier)` | Append a single modifier to armor stats |
| `.addArmorStatModifiers(Map)` | Append multiple modifiers to armor stats |
| `.addWeaponStatModifier(int statIndex, Modifier)` | Append a single modifier to weapon stats |
| `.addWeaponStatModifiers(Map)` | Append multiple modifiers to weapon stats |

```java
// Example: add +5 bonus Health to an armor piece, preserving original modifiers
int healthIndex = DefaultEntityStatTypes.getHealth();
Modifier bonus = new Modifier(ModifierTarget.Max, CalculationType.Additive, 5.0f);

ItemVisualOverrides overrides = ItemVisualOverrides.builder()
    .addArmorStatModifier(healthIndex, bonus)
    .build();
```

#### Entity Stat Indices

Stat indices are resolved at runtime. Use `DefaultEntityStatTypes` to get the correct index:

| Method | Stat |
| :--- | :--- |
| `DefaultEntityStatTypes.getHealth()` | Health |
| `DefaultEntityStatTypes.getMana()` | Mana |
| `DefaultEntityStatTypes.getStamina()` | Stamina |
| `DefaultEntityStatTypes.getOxygen()` | Oxygen |
| `DefaultEntityStatTypes.getSignatureEnergy()` | Signature Energy |
| `DefaultEntityStatTypes.getAmmo()` | Ammo |

> **Tip:** Do not use hardcoded stat indices (e.g. `0`, `1`). The mapping is loaded from game assets at runtime and may differ between versions.

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
If you change a config value (e.g. changed a damage value), you need to force a refresh so players see the change immediately.

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

See the [LICENSE](LICENSE) file for details.
Free to use in any project, but you cannot sell the library itself or claim it as your own.
Mods created with this lib must be more than just a slightly modified copy.
