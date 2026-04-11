package org.herolias.tooltips.internal;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.ItemBase;
import com.hypixel.hytale.protocol.ItemTranslationProperties;
import com.hypixel.hytale.protocol.UpdateType;
import com.hypixel.hytale.protocol.packets.assets.UpdateItems;
import com.hypixel.hytale.protocol.packets.assets.UpdateTranslations;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages global tooltip properties that affect all items of a specific base type,
 * without using virtual IDs. This is useful for system-wide tooltips that everyone should see.
 * <p>
 * Supports two modes:
 * <ul>
 *   <li><b>Regular (translation-key-wide):</b> Modifies the shared description translation
 *       key — affects all item types that share the same key.</li>
 *   <li><b>Item-ID-specific:</b> Overrides the item definition to use a unique description
 *       key, so only the exact item type is affected. Other items sharing the original
 *       translation key remain unchanged.</li>
 * </ul>
 */
public class GlobalTooltipManager {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private static final String ITEM_SPECIFIC_DESC_KEY_PREFIX = "server.items.dynamic.global.";

    private final VirtualItemRegistry virtualItemRegistry;

    // Regular (translation-key-wide) global tooltip maps
    private final Map<String, List<GlobalTooltipLine>> additiveLines = new ConcurrentHashMap<>();
    private final Map<String, List<GlobalTooltipLine>> replacedLines = new ConcurrentHashMap<>();

    // Item-ID-specific global tooltip maps (only affect the exact item type)
    private final Map<String, List<GlobalTooltipLine>> itemSpecificAdditiveLines = new ConcurrentHashMap<>();
    private final Map<String, List<GlobalTooltipLine>> itemSpecificReplacedLines = new ConcurrentHashMap<>();

    public GlobalTooltipManager(@Nonnull VirtualItemRegistry virtualItemRegistry) {
        this.virtualItemRegistry = virtualItemRegistry;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Item-specific description key
    // ─────────────────────────────────────────────────────────────────────

    @Nonnull
    public static String getItemSpecificDescriptionKey(@Nonnull String baseItemId) {
        return ITEM_SPECIFIC_DESC_KEY_PREFIX + baseItemId + ".description";
    }

    public boolean hasItemSpecificOverride(@Nonnull String baseItemId) {
        return itemSpecificAdditiveLines.containsKey(baseItemId)
                || itemSpecificReplacedLines.containsKey(baseItemId);
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Regular (translation-key-wide) global tooltip methods
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Appends a line to the global tooltip of an item type.
     * This modifies the shared description translation key, affecting all item types
     * that share the same key.
     * @param baseItemId the base item ID
     * @param line the line to add
     */
    public void addGlobalLine(@Nonnull String baseItemId, @Nonnull String line) {
        additiveLines.computeIfAbsent(baseItemId, k -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(new GlobalTooltipLine(line, false));
        broadcastRegularUpdates(Collections.singleton(baseItemId));
    }

    /**
     * Appends a translation key line to the global tooltip of an item type.
     * @param baseItemId the base item ID
     * @param translationKey the key to add
     */
    public void addGlobalTranslationLine(@Nonnull String baseItemId, @Nonnull String translationKey) {
        additiveLines.computeIfAbsent(baseItemId, k -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(new GlobalTooltipLine(translationKey, true));
        broadcastRegularUpdates(Collections.singleton(baseItemId));
    }

    /**
     * Replaces the global tooltip of an item type with the given lines.
     * @param baseItemId the base item ID
     * @param lines the lines to replace the description with
     */
    public void replaceGlobalTooltip(@Nonnull String baseItemId, @Nonnull String[] lines) {
        List<GlobalTooltipLine> mapped = new ArrayList<>();
        for (String line : lines) mapped.add(new GlobalTooltipLine(line, false));
        replacedLines.put(baseItemId, mapped);
        broadcastRegularUpdates(Collections.singleton(baseItemId));
    }

    /**
     * Replaces the global tooltip of an item type with the given translation keys.
     * @param baseItemId the base item ID
     * @param translationKeys the keys to replace the description with
     */
    public void replaceGlobalTranslationTooltip(@Nonnull String baseItemId, @Nonnull String[] translationKeys) {
        List<GlobalTooltipLine> mapped = new ArrayList<>();
        for (String key : translationKeys) mapped.add(new GlobalTooltipLine(key, true));
        replacedLines.put(baseItemId, mapped);
        broadcastRegularUpdates(Collections.singleton(baseItemId));
    }

    /**
     * Clears all regular (translation-key-wide) global tooltip overrides for this base item type.
     * @param baseItemId the base item ID
     */
    public void clearGlobalTooltips(@Nonnull String baseItemId) {
        boolean removedAdd = additiveLines.remove(baseItemId) != null;
        boolean removedRep = replacedLines.remove(baseItemId) != null;
        if (removedAdd || removedRep) {
            broadcastRegularUpdates(Collections.singleton(baseItemId));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Item-ID-specific global tooltip methods
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Appends a line to the global tooltip of an item type, affecting <b>only</b>
     * this exact item type. Other items sharing the same description translation
     * key are not affected.
     * <p>
     * Internally, this overrides the item's definition to point to a unique
     * description key, then sets that key's value.
     *
     * @param baseItemId the base item ID
     * @param line the line to add
     */
    public void addItemGlobalLine(@Nonnull String baseItemId, @Nonnull String line) {
        itemSpecificAdditiveLines.computeIfAbsent(baseItemId, k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                .add(new GlobalTooltipLine(line, false));
        broadcastItemSpecificUpdates(Collections.singleton(baseItemId));
    }

    /**
     * Appends a translation key line to the item-specific global tooltip.
     * @param baseItemId the base item ID
     * @param translationKey the translation key to add
     */
    public void addItemGlobalTranslationLine(@Nonnull String baseItemId, @Nonnull String translationKey) {
        itemSpecificAdditiveLines.computeIfAbsent(baseItemId, k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                .add(new GlobalTooltipLine(translationKey, true));
        broadcastItemSpecificUpdates(Collections.singleton(baseItemId));
    }

    /**
     * Replaces the global tooltip of <b>only</b> this exact item type.
     * @param baseItemId the base item ID
     * @param lines the lines to replace the description with
     */
    public void replaceItemGlobalTooltip(@Nonnull String baseItemId, @Nonnull String[] lines) {
        List<GlobalTooltipLine> mapped = new ArrayList<>();
        for (String line : lines) mapped.add(new GlobalTooltipLine(line, false));
        itemSpecificReplacedLines.put(baseItemId, mapped);
        broadcastItemSpecificUpdates(Collections.singleton(baseItemId));
    }

    /**
     * Replaces the global tooltip of <b>only</b> this exact item type with
     * the given translation keys.
     * @param baseItemId the base item ID
     * @param translationKeys the translation keys to replace the description with
     */
    public void replaceItemGlobalTranslationTooltip(@Nonnull String baseItemId, @Nonnull String[] translationKeys) {
        List<GlobalTooltipLine> mapped = new ArrayList<>();
        for (String key : translationKeys) mapped.add(new GlobalTooltipLine(key, true));
        itemSpecificReplacedLines.put(baseItemId, mapped);
        broadcastItemSpecificUpdates(Collections.singleton(baseItemId));
    }

    /**
     * Clears all item-ID-specific global tooltip overrides for this base item type
     * and restores the original item definition so the item uses the standard
     * (potentially shared) description key again.
     *
     * @param baseItemId the base item ID
     */
    public void clearItemGlobalTooltips(@Nonnull String baseItemId) {
        boolean removedAdd = itemSpecificAdditiveLines.remove(baseItemId) != null;
        boolean removedRep = itemSpecificReplacedLines.remove(baseItemId) != null;
        if (removedAdd || removedRep) {
            broadcastItemBaseRestore(baseItemId);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Sending & broadcasting
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Invoked when a player joins or needs a full refresh of all global items.
     * Sends both regular and item-specific global tooltip updates.
     * @param playerRef the player reference
     */
    public void sendAllUpdates(@Nonnull PlayerRef playerRef) {
        Set<String> regularIds = new HashSet<>();
        regularIds.addAll(additiveLines.keySet());
        regularIds.addAll(replacedLines.keySet());
        sendRegularUpdates(playerRef, regularIds);

        Set<String> itemSpecificIds = new HashSet<>();
        itemSpecificIds.addAll(itemSpecificAdditiveLines.keySet());
        itemSpecificIds.addAll(itemSpecificReplacedLines.keySet());
        if (!itemSpecificIds.isEmpty()) {
            sendItemSpecificUpdates(playerRef, itemSpecificIds);
        }
    }

    // ── Regular broadcasting ──

    private void broadcastRegularUpdates(@Nonnull Set<String> baseItemIds) {
        if (Universe.get() == null) return;

        for (PlayerRef playerRef : Universe.get().getPlayers()) {
            if (playerRef == null || !playerRef.isValid()) continue;
            sendRegularUpdates(playerRef, baseItemIds);
        }
    }

    private void sendRegularUpdates(@Nonnull PlayerRef playerRef, @Nonnull Set<String> baseItemIds) {
        if (baseItemIds.isEmpty()) return;

        String locale = playerRef.getLanguage();
        if (locale == null || locale.isEmpty()) locale = "en-US";

        Map<String, String> translations = new HashMap<>();

        for (String baseItemId : baseItemIds) {
            String translationKey = virtualItemRegistry.getItemDescriptionKey(baseItemId);
            if (translationKey == null || translationKey.trim().isEmpty()) continue;

            String computed = computeRegularGlobalDescription(baseItemId, locale);
            if (computed != null) {
                translations.put(translationKey, computed);
            }
        }

        if (!translations.isEmpty()) {
            try {
                UpdateTranslations packet = new UpdateTranslations(UpdateType.AddOrUpdate, translations);
                if (playerRef.getPacketHandler() != null) {
                    playerRef.getPacketHandler().writeNoCache(packet);
                }
            } catch (Exception e) {
                LOGGER.atWarning().log("Failed to send global tooltip updates: " + e.getMessage());
            }
        }
    }

    // ── Item-specific broadcasting ──

    private void broadcastItemSpecificUpdates(@Nonnull Set<String> baseItemIds) {
        if (Universe.get() == null) return;

        for (PlayerRef playerRef : Universe.get().getPlayers()) {
            if (playerRef == null || !playerRef.isValid()) continue;
            sendItemSpecificUpdates(playerRef, baseItemIds);
        }
    }

    /**
     * Sends item-specific global tooltip updates: an {@link UpdateItems} to override
     * the item's description key, and an {@link UpdateTranslations} with the
     * computed description under the unique key.
     */
    private void sendItemSpecificUpdates(@Nonnull PlayerRef playerRef, @Nonnull Set<String> baseItemIds) {
        if (baseItemIds.isEmpty()) return;

        String locale = playerRef.getLanguage();
        if (locale == null || locale.isEmpty()) locale = "en-US";

        Map<String, ItemBase> itemOverrides = new LinkedHashMap<>();
        Map<String, String> translations = new LinkedHashMap<>();

        for (String baseItemId : baseItemIds) {
            ItemBase override = createItemBaseOverride(baseItemId);
            if (override != null) {
                itemOverrides.put(baseItemId, override);
            }

            String uniqueKey = getItemSpecificDescriptionKey(baseItemId);
            String computed = getItemSpecificDescription(baseItemId, locale);
            if (computed != null) {
                translations.put(uniqueKey, computed);
            }
        }

        // Send UpdateItems first so the client knows about the new description key
        if (!itemOverrides.isEmpty()) {
            try {
                UpdateItems packet = new UpdateItems();
                packet.type = UpdateType.AddOrUpdate;
                packet.items = itemOverrides;
                packet.removedItems = new String[0];
                packet.updateModels = false;
                packet.updateIcons = false;
                if (playerRef.getPacketHandler() != null) {
                    playerRef.getPacketHandler().writeNoCache(packet);
                }
            } catch (Exception e) {
                LOGGER.atWarning().log("Failed to send UpdateItems for item-specific globals: " + e.getMessage());
            }
        }

        // Then send the translation values for the unique keys
        if (!translations.isEmpty()) {
            try {
                UpdateTranslations packet = new UpdateTranslations(UpdateType.AddOrUpdate, translations);
                if (playerRef.getPacketHandler() != null) {
                    playerRef.getPacketHandler().writeNoCache(packet);
                }
            } catch (Exception e) {
                LOGGER.atWarning().log("Failed to send UpdateTranslations for item-specific globals: " + e.getMessage());
            }
        }
    }

    // ── ItemBase override helpers ──

    /**
     * Creates a clone of the item's {@link ItemBase} with its description translation
     * key redirected to a unique item-specific key.
     */
    @Nullable
    private ItemBase createItemBaseOverride(@Nonnull String baseItemId) {
        try {
            Item originalItem = Item.getAssetMap().getAsset(baseItemId);
            if (originalItem == null) return null;

            ItemBase original = originalItem.toPacket();
            if (original == null) return null;

            ItemBase clone = original.clone();
            if (clone.translationProperties != null) {
                clone.translationProperties = clone.translationProperties.clone();
            } else {
                clone.translationProperties = new ItemTranslationProperties();
            }
            clone.translationProperties.description = getItemSpecificDescriptionKey(baseItemId);
            return clone;
        } catch (Exception e) {
            LOGGER.atWarning().log("Failed to create item base override for " + baseItemId + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Restores the original {@link ItemBase} for an item after its item-specific
     * global tooltip has been cleared.
     */
    private void broadcastItemBaseRestore(@Nonnull String baseItemId) {
        if (Universe.get() == null) return;

        try {
            Item originalItem = Item.getAssetMap().getAsset(baseItemId);
            if (originalItem == null) return;

            ItemBase originalBase = originalItem.toPacket();
            if (originalBase == null) return;

            Map<String, ItemBase> items = new HashMap<>();
            items.put(baseItemId, originalBase);

            for (PlayerRef playerRef : Universe.get().getPlayers()) {
                if (playerRef == null || !playerRef.isValid()) continue;
                try {
                    UpdateItems packet = new UpdateItems();
                    packet.type = UpdateType.AddOrUpdate;
                    packet.items = items;
                    packet.removedItems = new String[0];
                    packet.updateModels = false;
                    packet.updateIcons = false;
                    if (playerRef.getPacketHandler() != null) {
                        playerRef.getPacketHandler().writeNoCache(packet);
                    }
                } catch (Exception e) {
                    LOGGER.atWarning().log("Failed to restore item base for player: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            LOGGER.atWarning().log("Failed to restore item base for " + baseItemId + ": " + e.getMessage());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Init packet injection
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Injects globally overridden tooltips into the given translation map.
     * Called when the server sends {@code UpdateTranslations(Init)}.
     */
    public void injectIntoInitPacket(@Nonnull UpdateTranslations packet, @Nullable String locale) {
        if (packet.translations == null) return;

        if (locale == null || locale.isEmpty()) locale = "en-US";

        // Regular globals → shared keys
        Set<String> regularIds = new HashSet<>();
        regularIds.addAll(additiveLines.keySet());
        regularIds.addAll(replacedLines.keySet());

        for (String baseItemId : regularIds) {
            String key = virtualItemRegistry.getItemDescriptionKey(baseItemId);
            if (key == null || key.trim().isEmpty()) continue;

            String computed = computeRegularGlobalDescriptionFromMap(baseItemId, packet.translations, locale);
            if (computed != null) {
                packet.translations.put(key, computed);
            }
        }

        // Item-specific globals → unique keys
        Set<String> itemSpecificIds = new HashSet<>();
        itemSpecificIds.addAll(itemSpecificAdditiveLines.keySet());
        itemSpecificIds.addAll(itemSpecificReplacedLines.keySet());

        for (String baseItemId : itemSpecificIds) {
            String uniqueKey = getItemSpecificDescriptionKey(baseItemId);
            String computed = getItemSpecificDescriptionFromMap(baseItemId, packet.translations, locale);
            if (computed != null) {
                packet.translations.put(uniqueKey, computed);
            }
        }
    }

    /**
     * Injects item-specific global tooltip overrides into an {@code UpdateItems(Init)}
     * packet, redirecting affected items to use unique description keys.
     */
    public void injectIntoInitItemsPacket(@Nonnull UpdateItems packet) {
        if (packet.items == null) return;

        Set<String> itemSpecificIds = new HashSet<>();
        itemSpecificIds.addAll(itemSpecificAdditiveLines.keySet());
        itemSpecificIds.addAll(itemSpecificReplacedLines.keySet());

        for (String baseItemId : itemSpecificIds) {
            ItemBase original = packet.items.get(baseItemId);
            if (original == null) continue;

            ItemBase clone = original.clone();
            if (clone.translationProperties != null) {
                clone.translationProperties = clone.translationProperties.clone();
            } else {
                clone.translationProperties = new ItemTranslationProperties();
            }
            clone.translationProperties.description = getItemSpecificDescriptionKey(baseItemId);
            packet.items.put(baseItemId, clone);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Description resolution
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Gets the full description for an item, considering both item-specific and
     * regular global overrides. Item-specific takes priority.
     * <p>
     * Used by the virtual-item system as the base description for provider enrichment.
     */
    @Nullable
    public String getGlobalDescription(@Nonnull String baseItemId, @Nonnull String locale) {
        String itemSpecific = getItemSpecificDescription(baseItemId, locale);
        if (itemSpecific != null) return itemSpecific;
        return computeRegularGlobalDescription(baseItemId, locale);
    }

    // ── Item-specific description ──

    @Nullable
    private String getItemSpecificDescription(@Nonnull String baseItemId, @Nonnull String locale) {
        List<GlobalTooltipLine> replace = itemSpecificReplacedLines.get(baseItemId);
        if (replace != null) {
            return String.join("\n", resolveLines(replace, locale));
        }

        List<GlobalTooltipLine> add = itemSpecificAdditiveLines.get(baseItemId);
        if (add != null && !add.isEmpty()) {
            String original = virtualItemRegistry.getOriginalDescription(baseItemId, locale);
            StringBuilder sb = new StringBuilder();
            if (original != null && !original.isEmpty()) {
                sb.append(original);
                sb.append("\n\n");
            }
            sb.append(String.join("\n", resolveLines(add, locale)));
            return sb.toString();
        }

        return null;
    }

    @Nullable
    private String getItemSpecificDescriptionFromMap(String baseItemId, Map<String, String> translationsMap, String fallbackLocale) {
        List<GlobalTooltipLine> replace = itemSpecificReplacedLines.get(baseItemId);
        if (replace != null) {
            return String.join("\n", resolveLinesFromMap(replace, translationsMap, fallbackLocale));
        }

        List<GlobalTooltipLine> add = itemSpecificAdditiveLines.get(baseItemId);
        if (add != null && !add.isEmpty()) {
            String descKey = virtualItemRegistry.getItemDescriptionKey(baseItemId);
            String original = translationsMap.get(descKey);
            if (original == null) {
                original = virtualItemRegistry.getOriginalDescription(baseItemId, fallbackLocale);
            }

            StringBuilder sb = new StringBuilder();
            if (original != null && !original.isEmpty()) {
                sb.append(original);
                sb.append("\n\n");
            }
            sb.append(String.join("\n", resolveLinesFromMap(add, translationsMap, fallbackLocale)));
            return sb.toString();
        }

        return null;
    }

    // ── Regular global description ──

    @Nullable
    private String computeRegularGlobalDescription(@Nonnull String baseItemId, @Nonnull String locale) {
        List<GlobalTooltipLine> replace = replacedLines.get(baseItemId);
        if (replace != null) {
            return String.join("\n", resolveLines(replace, locale));
        }

        List<GlobalTooltipLine> add = additiveLines.get(baseItemId);
        if (add != null && !add.isEmpty()) {
            String original = virtualItemRegistry.getOriginalDescription(baseItemId, locale);
            StringBuilder sb = new StringBuilder();

            if (original != null && !original.isEmpty()) {
                sb.append(original);
                sb.append("\n\n");
            }
            sb.append(String.join("\n", resolveLines(add, locale)));
            return sb.toString();
        }

        return virtualItemRegistry.getOriginalDescription(baseItemId, locale);
    }

    @Nullable
    private String computeRegularGlobalDescriptionFromMap(String baseItemId, Map<String, String> translationsMap, String fallbackLocale) {
        List<GlobalTooltipLine> replace = replacedLines.get(baseItemId);
        if (replace != null) {
            return String.join("\n", resolveLinesFromMap(replace, translationsMap, fallbackLocale));
        }

        List<GlobalTooltipLine> add = additiveLines.get(baseItemId);
        if (add != null && !add.isEmpty()) {
            String descKey = virtualItemRegistry.getItemDescriptionKey(baseItemId);
            String original = translationsMap.get(descKey);
            if (original == null) {
                original = virtualItemRegistry.getOriginalDescription(baseItemId, fallbackLocale);
            }

            StringBuilder sb = new StringBuilder();
            if (original != null && !original.isEmpty()) {
                sb.append(original);
                sb.append("\n\n");
            }
            sb.append(String.join("\n", resolveLinesFromMap(add, translationsMap, fallbackLocale)));
            return sb.toString();
        }

        return null;
    }

    // ── Line resolution helpers ──

    private List<String> resolveLines(List<GlobalTooltipLine> lines, String locale) {
        List<String> resolved = new ArrayList<>();
        com.hypixel.hytale.server.core.modules.i18n.I18nModule i18n = null;
        try {
            i18n = com.hypixel.hytale.server.core.modules.i18n.I18nModule.get();
        } catch (Exception ignored) {}

        for (GlobalTooltipLine line : lines) {
            if (line.isTranslationKey && i18n != null) {
                String msg = i18n.getMessage(locale, line.text);
                resolved.add(msg != null ? msg : line.text);
            } else {
                resolved.add(line.text);
            }
        }
        return resolved;
    }

    private List<String> resolveLinesFromMap(List<GlobalTooltipLine> lines, Map<String, String> translationsMap, String fallbackLocale) {
        List<String> resolved = new ArrayList<>();
        com.hypixel.hytale.server.core.modules.i18n.I18nModule i18n = null;
        try {
            i18n = com.hypixel.hytale.server.core.modules.i18n.I18nModule.get();
        } catch (Exception ignored) {}

        for (GlobalTooltipLine line : lines) {
            if (line.isTranslationKey) {
                String msg = translationsMap.get(line.text);
                if (msg == null && i18n != null) {
                    msg = i18n.getMessage(fallbackLocale, line.text);
                }
                resolved.add(msg != null ? msg : line.text);
            } else {
                resolved.add(line.text);
            }
        }
        return resolved;
    }
}
