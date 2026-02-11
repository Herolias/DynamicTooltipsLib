package org.herolias.tooltips.internal;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.ItemBase;
import com.hypixel.hytale.protocol.ItemTranslationProperties;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.modules.i18n.I18nModule;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages "virtual" item definitions used to give each item instance
 * a unique tooltip without changing the server-side item ID.
 *
 * <h2>How it works</h2>
 * Hytale's tooltip system resolves item descriptions via translation keys that
 * are defined per item <em>type</em>.  All items of the same type share the same
 * description key, making it impossible to display different tooltips on two
 * items of the same base type.
 * <p>
 * This registry solves that by creating lightweight <b>virtual item definitions</b>:
 * <ul>
 *   <li>Each unique (baseItemId + combinedHash) pair gets a deterministic
 *       virtual ID, e.g. {@code Tool_Pickaxe_Adamantite__dtt_a1b2c3d4}.</li>
 *   <li>The virtual item's {@link ItemBase} is a deep clone of the original with
 *       only the {@code id} and {@code translationProperties} changed.</li>
 *   <li>Virtual items are sent to individual players via {@code UpdateItems}
 *       packets — they are <b>never registered</b> in the server's global asset store.</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * All internal maps use {@link ConcurrentHashMap}. Safe for concurrent use
 * from multiple world threads.
 */
public class VirtualItemRegistry {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** Separator between the base item ID and the tooltip hash. */
    public static final String VIRTUAL_SEPARATOR = "__dtt_";

    /** Prefix for virtual item description translation keys. */
    private static final String DESC_KEY_PREFIX = "server.items.dynamic.";

    /** Prefix for virtual item name translation keys. */
    private static final String NAME_KEY_PREFIX = "server.items.dynamic.";

    /**
     * Global cache: virtual item ID → cloned {@link ItemBase}.
     * Populated lazily.
     */
    private final ConcurrentHashMap<String, ItemBase> virtualItemCache = new ConcurrentHashMap<>();

    /**
     * Per-player tracking: which virtual item IDs have been sent to each player.
     */
    private final ConcurrentHashMap<UUID, Set<String>> sentToPlayer = new ConcurrentHashMap<>();

    /**
     * Per-player slot tracking: maps inventory slot keys to virtual item IDs.
     */
    private final ConcurrentHashMap<UUID, ConcurrentHashMap<String, String>> playerSlotVirtualIds = new ConcurrentHashMap<>();

    /**
     * Per-player tracking of which real item types currently have their description
     * translation overridden in interactive sections.
     */
    private final ConcurrentHashMap<UUID, Set<String>> playerHotbarOverrides = new ConcurrentHashMap<>();

    /** Cache: base item ID → description translation key. */
    private final ConcurrentHashMap<String, String> descriptionKeyCache = new ConcurrentHashMap<>();

    /** Cache: base item ID → name translation key. */
    private final ConcurrentHashMap<String, String> nameKeyCache = new ConcurrentHashMap<>();

    /** Cache: "language:baseItemId" → original description text. */
    private final ConcurrentHashMap<String, String> originalDescriptionCache = new ConcurrentHashMap<>();

    /** Cache: virtualId → built description string. */
    private final ConcurrentHashMap<String, String> builtDescriptionCache = new ConcurrentHashMap<>();

    // ─────────────────────────────────────────────────────────────────────
    //  Virtual ID generation
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Generates a deterministic virtual item ID for the given base item + hash.
     *
     * @param baseItemId   the real item ID
     * @param combinedHash the hash from {@link TooltipRegistry.ComposedTooltip#getCombinedHash()}
     * @return a virtual ID, e.g. {@code "Tool_Pickaxe_Adamantite__dtt_a1b2c3d4"}
     */
    @Nonnull
    public String generateVirtualId(@Nonnull String baseItemId, @Nonnull String combinedHash) {
        return baseItemId + VIRTUAL_SEPARATOR + combinedHash;
    }

    /**
     * Extracts the original (base) item ID from a virtual ID.
     *
     * @return the base item ID, or {@code null} if the given ID is not virtual
     */
    @Nullable
    public static String getBaseItemId(@Nonnull String virtualOrRealId) {
        int idx = virtualOrRealId.indexOf(VIRTUAL_SEPARATOR);
        return idx > 0 ? virtualOrRealId.substring(0, idx) : null;
    }

    /**
     * Checks whether the given item ID is a virtual tooltip ID.
     */
    public static boolean isVirtualId(@Nonnull String itemId) {
        return itemId.contains(VIRTUAL_SEPARATOR);
    }

    /**
     * Gets the unique description translation key for a virtual item.
     */
    @Nonnull
    public static String getVirtualDescriptionKey(@Nonnull String virtualId) {
        return DESC_KEY_PREFIX + virtualId + ".description";
    }

    /**
     * Gets the unique name translation key for a virtual item.
     */
    @Nonnull
    public static String getVirtualNameKey(@Nonnull String virtualId) {
        return NAME_KEY_PREFIX + virtualId + ".name";
    }

    // ─────────────────────────────────────────────────────────────────────
    //  ItemBase management
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Gets or creates the {@link ItemBase} protocol packet for a virtual item.
     * <p>
     * The returned {@code ItemBase} is a deep clone of the original item's protocol
     * representation, with the {@code id} and {@code translationProperties}
     * changed to point to unique virtual keys.
     *
     * @param baseItemId  the real item ID to clone from
     * @param virtualId   the virtual item ID to assign
     * @param nameOverride if non-null, the virtual item gets its own name key
     * @return the virtual {@code ItemBase}, or {@code null} if the base item was not found
     */
    @Nullable
    public ItemBase getOrCreateVirtualItemBase(@Nonnull String baseItemId,
                                               @Nonnull String virtualId,
                                               @Nullable String nameOverride) {
        // Use a cache key that includes whether there's a name override
        String cacheKey = nameOverride != null ? virtualId + ":named" : virtualId;

        return virtualItemCache.computeIfAbsent(cacheKey, k -> {
            try {
                Item originalItem = Item.getAssetMap().getAsset(baseItemId);
                if (originalItem == null) {
                    LOGGER.atWarning().log("Cannot create virtual item: base item not found: " + baseItemId);
                    return null;
                }

                ItemBase originalPacket = originalItem.toPacket();
                if (originalPacket == null) {
                    LOGGER.atWarning().log("Cannot create virtual item: toPacket() returned null for: " + baseItemId);
                    return null;
                }

                // Deep clone — we must not modify the cached original
                ItemBase clone = originalPacket.clone();
                clone.id = virtualId;

                // Prevent virtual items from appearing in the creative inventory.
                // categories controls which creative library tabs show the item;
                // variant = true causes the client to hide it by default.
                clone.categories = null;
                clone.variant = true;

                // Give the virtual item its own unique description translation key.
                String virtualDescKey = getVirtualDescriptionKey(virtualId);
                if (clone.translationProperties != null) {
                    clone.translationProperties = clone.translationProperties.clone();
                    clone.translationProperties.description = virtualDescKey;
                    if (nameOverride != null) {
                        clone.translationProperties.name = getVirtualNameKey(virtualId);
                    }
                } else {
                    clone.translationProperties = new ItemTranslationProperties();
                    clone.translationProperties.name = nameOverride != null
                            ? getVirtualNameKey(virtualId)
                            : "server.items." + baseItemId + ".name";
                    clone.translationProperties.description = virtualDescKey;
                }

                return clone;
            } catch (Exception e) {
                LOGGER.atWarning().log("Failed to create virtual item for " + virtualId + ": " + e.getMessage());
                return null;
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Per-player sent-item tracking
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Returns the subset of {@code virtualIds} that have <b>not</b> yet been sent
     * to the given player, and marks all of them as sent.
     */
    @Nonnull
    public Set<String> markAndGetUnsent(@Nonnull UUID playerUuid, @Nonnull Set<String> virtualIds) {
        Set<String> sentSet = sentToPlayer.computeIfAbsent(playerUuid, k -> ConcurrentHashMap.newKeySet());
        Set<String> unsent = new HashSet<>();
        for (String vId : virtualIds) {
            if (sentSet.add(vId)) {
                unsent.add(vId);
            }
        }
        return unsent;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Per-player slot-to-virtualId tracking
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Records the virtual ID currently occupying a given inventory slot for a player.
     */
    public void trackSlotVirtualId(@Nonnull UUID playerUuid, @Nonnull String slotKey, @Nullable String virtualId) {
        ConcurrentHashMap<String, String> slotMap = playerSlotVirtualIds.computeIfAbsent(playerUuid, k -> new ConcurrentHashMap<>());
        if (virtualId != null) {
            slotMap.put(slotKey, virtualId);
        } else {
            slotMap.remove(slotKey);
        }
    }

    /**
     * Looks up the virtual ID currently in a given inventory slot for a player.
     */
    @Nullable
    public String getSlotVirtualId(@Nonnull UUID playerUuid, @Nonnull String slotKey) {
        Map<String, String> slotMap = playerSlotVirtualIds.get(playerUuid);
        return slotMap != null ? slotMap.get(slotKey) : null;
    }

    /**
     * Searches all tracked slots for a player to find a virtual ID whose base item ID
     * matches the given real item ID.
     */
    @Nullable
    public String findVirtualIdForBaseItem(@Nonnull UUID playerUuid, @Nonnull String realItemId) {
        Map<String, String> slotMap = playerSlotVirtualIds.get(playerUuid);
        if (slotMap == null || slotMap.isEmpty()) return null;

        // First pass: check hotbar slots
        for (Map.Entry<String, String> entry : slotMap.entrySet()) {
            if (entry.getKey().startsWith("hotbar:")) {
                String baseId = getBaseItemId(entry.getValue());
                if (realItemId.equals(baseId)) return entry.getValue();
            }
        }

        // Second pass: any slot
        for (Map.Entry<String, String> entry : slotMap.entrySet()) {
            String baseId = getBaseItemId(entry.getValue());
            if (realItemId.equals(baseId)) return entry.getValue();
        }

        return null;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Hotbar description-override tracking
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Atomically replaces the set of real item types whose description translations
     * are currently overridden for a player's hotbar.
     *
     * @return the <b>previous</b> set of overridden types, or {@code null} if none
     */
    @Nullable
    public Set<String> getAndUpdateHotbarOverrides(@Nonnull UUID playerUuid,
                                                   @Nonnull Set<String> currentOverrides) {
        if (currentOverrides.isEmpty()) {
            return playerHotbarOverrides.remove(playerUuid);
        }
        return playerHotbarOverrides.put(playerUuid, new HashSet<>(currentOverrides));
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Description resolution
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Returns the description translation key for a base item type.
     */
    @Nonnull
    public String getItemDescriptionKey(@Nonnull String baseItemId) {
        return resolveDescriptionKey(baseItemId);
    }

    /**
     * Returns the name translation key for a base item type.
     */
    @Nonnull
    public String getItemNameKey(@Nonnull String baseItemId) {
        return nameKeyCache.computeIfAbsent(baseItemId, id -> {
            try {
                Item item = Item.getAssetMap().getAsset(id);
                if (item != null) {
                    // Use the item's packet to read the actual translation properties
                    ItemBase packet = item.toPacket();
                    if (packet != null && packet.translationProperties != null
                            && packet.translationProperties.name != null) {
                        return packet.translationProperties.name;
                    }
                }
            } catch (Exception e) {
                // Fall through
            }
            return "server.items." + id + ".name";
        });
    }

    /**
     * Resolves the original (unmodified) description for a base item ID.
     */
    @Nonnull
    public String getOriginalDescription(@Nonnull String baseItemId, @Nullable String language) {
        String cacheKey = (language != null ? language : "_default") + ":" + baseItemId;
        return originalDescriptionCache.computeIfAbsent(cacheKey, k -> {
            try {
                String descKey = resolveDescriptionKey(baseItemId);
                I18nModule i18n = I18nModule.get();
                if (i18n == null) return "";
                String msg = i18n.getMessage(language, descKey);
                return msg != null ? msg : "";
            } catch (Exception e) {
                return "";
            }
        });
    }

    @Nonnull
    private String resolveDescriptionKey(@Nonnull String baseItemId) {
        return descriptionKeyCache.computeIfAbsent(baseItemId, id -> {
            try {
                Item item = Item.getAssetMap().getAsset(id);
                if (item != null) {
                    return item.getDescriptionTranslationKey();
                }
            } catch (Exception e) {
                LOGGER.atFine().log("Could not resolve description key for " + id + ": " + e.getMessage());
            }
            return "server.items." + id + ".description";
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Built description caching
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Gets a cached built description, or caches the given one.
     */
    @Nonnull
    public String cacheDescription(@Nonnull String virtualId, @Nonnull String description) {
        return builtDescriptionCache.computeIfAbsent(virtualId, k -> description);
    }

    @Nullable
    public String getCachedDescription(@Nonnull String virtualId) {
        return builtDescriptionCache.get(virtualId);
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Lifecycle
    // ─────────────────────────────────────────────────────────────────────

    public void onPlayerLeave(@Nonnull UUID playerUuid) {
        sentToPlayer.remove(playerUuid);
        playerSlotVirtualIds.remove(playerUuid);
        playerHotbarOverrides.remove(playerUuid);
    }

    /**
     * Invalidates per-player caches and the built-description cache for a
     * specific player. Unlike {@link #onPlayerLeave}, this preserves global
     * item-base caches (only clears things that affect the player's tooltip
     * output so that the next packet triggers a full re-send).
     */
    public void invalidatePlayer(@Nonnull UUID playerUuid) {
        sentToPlayer.remove(playerUuid);
        playerSlotVirtualIds.remove(playerUuid);
        playerHotbarOverrides.remove(playerUuid);
        // Clear built descriptions so they are recomposed with fresh text
        builtDescriptionCache.clear();
    }

    public void clearCache() {
        virtualItemCache.clear();
        sentToPlayer.clear();
        playerSlotVirtualIds.clear();
        playerHotbarOverrides.clear();
        descriptionKeyCache.clear();
        nameKeyCache.clear();
        originalDescriptionCache.clear();
        builtDescriptionCache.clear();
    }
}
