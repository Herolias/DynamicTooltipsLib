package org.herolias.tooltips.internal;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.ItemBase;
import com.hypixel.hytale.protocol.ItemArmor;
import com.hypixel.hytale.protocol.ItemEntityConfig;
import com.hypixel.hytale.protocol.ItemTranslationProperties;
import com.hypixel.hytale.protocol.ItemWeapon;
import com.hypixel.hytale.protocol.Modifier;
import com.hypixel.hytale.protocol.ItemResourceType;
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
    /**
     * Global cache: virtual item ID → cloned {@link ItemBase}.
     * <p>
     * Bounded LRU cache to prevent memory leaks from infinite dynamic tooltips.
     */
    private final Map<String, ItemBase> virtualItemCache = Collections.synchronizedMap(new LRUCache<>(10000));

    /**
     * Per-player tracking: which virtual item IDs have been sent to each player.
     */
    private final ConcurrentHashMap<UUID, Set<String>> sentToPlayer = new ConcurrentHashMap<>();

    /**
     * Per-player slot tracking: maps inventory slot keys to virtual item IDs.
     */
    private final ConcurrentHashMap<UUID, ConcurrentHashMap<String, String>> playerSlotVirtualIds = new ConcurrentHashMap<>();



    /** Cache: base item ID → description translation key. */
    private final ConcurrentHashMap<String, String> descriptionKeyCache = new ConcurrentHashMap<>();



    /** Cache: "language:baseItemId" → original description text. */
    private final ConcurrentHashMap<String, String> originalDescriptionCache = new ConcurrentHashMap<>();

    /** Cache: "language:baseItemId" → original name text. */
    private final ConcurrentHashMap<String, String> originalNameCache = new ConcurrentHashMap<>();

    /**
     * Cache: virtualId → built description string.
     * <p>
     * Bounded LRU cache.
     */
    private final Map<String, String> builtDescriptionCache = Collections.synchronizedMap(new LRUCache<>(10000));

    /**
     * Lazily-populated cache: qualityIndex → ItemEntityConfig (protocol form).
     * Used to auto-resolve rarity particles when qualityIndex is overridden.
     */
    private volatile Map<Integer, ItemEntityConfig> qualityEntityConfigCache;

    /**
     * Simple thread-safe LRU Cache implementation.
     */
    private static class LRUCache<K, V> extends LinkedHashMap<K, V> {
        private final int maxEntries;

        public LRUCache(int maxEntries) {
            super(maxEntries, 0.75f, true);
            this.maxEntries = maxEntries;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
            return size() > maxEntries;
        }
    }

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
    public static String generateVirtualId(@Nonnull String baseItemId, @Nonnull String combinedHash) {
        // BaseID__dtt_HASH
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
     * @param nameOverride if non-null, the virtual item gets its own name key (used for text overrides)
     * @param visualOverrides optional visual property overrides
     * @param nameTranslationKey if non-null, specific translation key to use for name
     * @param descriptionTranslationKey if non-null, specific translation key to use for description
     * @return the virtual {@code ItemBase}, or {@code null} if the base item was not found
     */
    @Nullable
    public ItemBase getOrCreateVirtualItemBase(@Nonnull String baseItemId,
                                               @Nonnull String virtualId,
                                               @Nullable String nameOverride,
                                               @Nullable org.herolias.tooltips.api.ItemVisualOverrides visualOverrides,
                                               @Nullable String nameTranslationKey,
                                               @Nullable String descriptionTranslationKey) {
        // Use a cache key that includes whether there's a name override or translation keys to differentiate variants.
        // We also support fallback to the opposite variant key so a later lookup
        // never rebuilds the same virtual ID with downgraded visuals.
        String cacheKey = virtualId +
            (nameOverride != null ? ":named" : "") +
            (nameTranslationKey != null ? ":nk=" + nameTranslationKey : "") +
            (descriptionTranslationKey != null ? ":dk=" + descriptionTranslationKey : "");
        
        ItemBase cached = virtualItemCache.get(cacheKey);
        if (cached != null) return cached;

        // Note: Fallback logic gets complicated with multiple dimensions. Since virtual ID 
        // includes the hash which includes keys, the virtualID itself is unique enough usually.
        // We stick to the specific cache key.

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

                // Apply visual overrides
                if (visualOverrides != null) {
                    if (visualOverrides.getModel() != null) clone.model = visualOverrides.getModel();
                    if (visualOverrides.getTexture() != null) clone.texture = visualOverrides.getTexture();
                    if (visualOverrides.getIcon() != null) clone.icon = visualOverrides.getIcon();
                    if (visualOverrides.getAnimation() != null) clone.animation = visualOverrides.getAnimation();
                    if (visualOverrides.getSoundEventIndex() != null) clone.soundEventIndex = visualOverrides.getSoundEventIndex();
                    if (visualOverrides.getScale() != null) clone.scale = visualOverrides.getScale();
                    if (visualOverrides.getQualityIndex() != null) clone.qualityIndex = visualOverrides.getQualityIndex();
                    if (visualOverrides.getLight() != null) clone.light = visualOverrides.getLight();
                    if (visualOverrides.getParticles() != null) clone.particles = visualOverrides.getParticles();
                    if (visualOverrides.getPlayerAnimationsId() != null) clone.playerAnimationsId = visualOverrides.getPlayerAnimationsId();
                    if (visualOverrides.getUsePlayerAnimations() != null) clone.usePlayerAnimations = visualOverrides.getUsePlayerAnimations();
                    if (visualOverrides.getReticleIndex() != null) clone.reticleIndex = visualOverrides.getReticleIndex();
                    if (visualOverrides.getIconProperties() != null) clone.iconProperties = visualOverrides.getIconProperties();
                    if (visualOverrides.getFirstPersonParticles() != null) clone.firstPersonParticles = visualOverrides.getFirstPersonParticles();
                    if (visualOverrides.getTrails() != null) clone.trails = visualOverrides.getTrails();
                    if (visualOverrides.getDroppedItemAnimation() != null) clone.droppedItemAnimation = visualOverrides.getDroppedItemAnimation();
                    if (visualOverrides.getItemSoundSetIndex() != null) clone.itemSoundSetIndex = visualOverrides.getItemSoundSetIndex();
                    if (visualOverrides.getItemAppearanceConditions() != null) clone.itemAppearanceConditions = visualOverrides.getItemAppearanceConditions();
                    if (visualOverrides.getPullbackConfig() != null) clone.pullbackConfig = visualOverrides.getPullbackConfig();
                    if (visualOverrides.getClipsGeometry() != null) clone.clipsGeometry = visualOverrides.getClipsGeometry();
                    if (visualOverrides.getRenderDeployablePreview() != null) clone.renderDeployablePreview = visualOverrides.getRenderDeployablePreview();
                    if (visualOverrides.getSet() != null) clone.set = visualOverrides.getSet();
                    if (visualOverrides.getCategories() != null) clone.categories = visualOverrides.getCategories();
                    if (visualOverrides.getDisplayEntityStatsHUD() != null) clone.displayEntityStatsHUD = visualOverrides.getDisplayEntityStatsHUD();
                    if (visualOverrides.getItemEntity() != null) clone.itemEntity = visualOverrides.getItemEntity();
                    if (visualOverrides.getDurability() != null) clone.durability = visualOverrides.getDurability();
                    if (visualOverrides.getArmor() != null) clone.armor = visualOverrides.getArmor();
                    if (visualOverrides.getWeapon() != null) clone.weapon = visualOverrides.getWeapon();
                    if (visualOverrides.getTool() != null) clone.tool = visualOverrides.getTool();

                    // ── Additive stat modifier merge ──
                    // Unlike the replace-style overrides above, these MERGE with
                    // the original item's existing stat modifiers.
                    if (visualOverrides.getAdditionalArmorStatModifiers() != null) {
                        if (clone.armor == null) clone.armor = new ItemArmor();
                        clone.armor.statModifiers = mergeModifierMaps(
                                clone.armor.statModifiers,
                                visualOverrides.getAdditionalArmorStatModifiers());
                    }
                    if (visualOverrides.getAdditionalWeaponStatModifiers() != null) {
                        if (clone.weapon == null) clone.weapon = new ItemWeapon();
                        clone.weapon.statModifiers = mergeModifierMaps(
                                clone.weapon.statModifiers,
                                visualOverrides.getAdditionalWeaponStatModifiers());
                    }
                }

                // ── Auto-resolve rarity particles ──
                // When qualityIndex is overridden but itemEntity is NOT explicitly
                // set by the provider, copy the particle config from a reference
                // item with the target quality so dropped items glow correctly.
                if (visualOverrides != null
                        && visualOverrides.getQualityIndex() != null
                        && visualOverrides.getItemEntity() == null) {
                    ItemEntityConfig refConfig = resolveQualityEntityConfig(visualOverrides.getQualityIndex());
                    if (refConfig != null) {
                        clone.itemEntity = refConfig.clone();
                    }
                }

                // Prevent double-counting in crafting grids by setting resource quantity to 0.
                // We MUST NOT set resourceTypes to null, because Furnaces and other machinery 
                // explicitly check for the presence of the resource type (e.g. "Fuel") 
                // to allow the item into the slot initially.
                if (clone.resourceTypes != null) {
                    ItemResourceType[] newResourceTypes = new ItemResourceType[clone.resourceTypes.length];
                    for (int i = 0; i < clone.resourceTypes.length; i++) {
                        newResourceTypes[i] = clone.resourceTypes[i].clone();
                        newResourceTypes[i].quantity = 0;
                    }
                    clone.resourceTypes = newResourceTypes;
                }

                // Prevent virtual items from appearing in the creative inventory.
                // categories controls which creative library tabs show the item;
                // variant = true causes the client to hide it by default.
                // clone.categories = null; // RESTORED: Needed for client-side ability HUD resolution
                clone.variant = true;

                // Give the virtual item its own unique description translation key.
                if (clone.translationProperties != null) {
                    clone.translationProperties = clone.translationProperties.clone();
                } else {
                    clone.translationProperties = new ItemTranslationProperties();
                }

                // Determine Description Key
                if (descriptionTranslationKey != null) {
                    clone.translationProperties.description = descriptionTranslationKey;
                } else {
                    clone.translationProperties.description = getVirtualDescriptionKey(virtualId);
                }

                // Determine Name Key
                if (nameTranslationKey != null) {
                    clone.translationProperties.name = nameTranslationKey;
                } else if (nameOverride != null) {
                    clone.translationProperties.name = getVirtualNameKey(virtualId);
                } else {
                     // If no override and no translation key, default to original name key logic
                    if (clone.translationProperties.name == null) { // if it was null initially
                         clone.translationProperties.name = "server.items." + baseItemId + ".name";
                    }
                }

                return clone;
            } catch (Exception e) {
                LOGGER.atWarning().log("Failed to create virtual item for " + virtualId + ": " + e.getMessage());
                return null;
            }
        });
    }

    /**
     * Lazily resolves the {@link ItemEntityConfig} (particle system, color, etc.)
     * associated with a given quality tier by scanning the item registry.
     * <p>
     * The result is cached so the registry is only scanned once.
     *
     * @param qualityIndex the target quality tier index
     * @return the matching config, or {@code null} if none was found
     */
    @Nullable
    private ItemEntityConfig resolveQualityEntityConfig(int qualityIndex) {
        Map<Integer, ItemEntityConfig> cache = this.qualityEntityConfigCache;
        if (cache == null) {
            synchronized (this) {
                cache = this.qualityEntityConfigCache;
                if (cache == null) {
                    cache = new HashMap<>();
                    try {
                        Map<String, Item> items = Item.getAssetMap().getAssetMap();
                        for (Item item : items.values()) {
                            int qi = item.getQualityIndex();
                            if (qi > 0 && !cache.containsKey(qi)) {
                                com.hypixel.hytale.server.core.asset.type.item.config.ItemEntityConfig serverCfg =
                                        item.getItemEntityConfig();
                                if (serverCfg != null) {
                                    // Convert the server-side config to the protocol form
                                    ItemBase packet = item.toPacket();
                                    if (packet.itemEntity != null) {
                                        cache.put(qi, packet.itemEntity.clone());
                                    }
                                }
                            }
                        }
                        LOGGER.atInfo().log("Cached ItemEntityConfig for " + cache.size() + " quality tiers");
                    } catch (Exception e) {
                        LOGGER.atWarning().log("Failed to build quality→entity config cache: " + e.getMessage());
                    }
                    this.qualityEntityConfigCache = cache;
                }
            }
        }
        return cache.get(qualityIndex);
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

    /**
     * Returns the set of virtual item IDs that have been sent to the given
     * player, or {@code null} if the player has no tracking data.
     */
    @Nullable
    public Set<String> getSentVirtualIds(@Nonnull UUID playerUuid) {
        return sentToPlayer.get(playerUuid);
    }

    /**
     * Looks up a cached virtual {@link ItemBase} by virtual ID.
     * Searches the cache using the exact virtual ID as prefix, since cache
     * keys may include additional suffixes (e.g. {@code ":named"}).
     *
     * @return the cached ItemBase, or {@code null} if not found
     */
    @Nullable
    public ItemBase getCachedVirtualItem(@Nonnull String virtualId) {
        // Direct lookup first (most common case: no name override)
        ItemBase direct = virtualItemCache.get(virtualId);
        if (direct != null) return direct;
        // Fallback: search for keys that start with the virtual ID
        // (handles ":named", ":nk=", ":dk=" suffixes)
        synchronized (virtualItemCache) {
            for (Map.Entry<String, ItemBase> entry : virtualItemCache.entrySet()) {
                if (entry.getKey().startsWith(virtualId)) {
                    return entry.getValue();
                }
            }
        }
        return null;
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

    /**
     * Resolves the original (unmodified) name for a base item ID.
     */
    @Nullable
    public String getOriginalName(@Nonnull String baseItemId, @Nullable String language) {
        String cacheKey = (language != null ? language : "_default") + ":" + baseItemId;
        return originalNameCache.computeIfAbsent(cacheKey, k -> {
            try {
                Item item = Item.getAssetMap().getAsset(baseItemId);
                if (item == null) return null;
                String nameKey = item.getTranslationKey();
                I18nModule i18n = I18nModule.get();
                if (i18n == null) return null;
                String msg = i18n.getMessage(language, nameKey);
                return msg;
            } catch (Exception e) {
                return null;
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
        // Clear built descriptions so they are recomposed with fresh text
        builtDescriptionCache.clear();
    }

    /**
     * Clears only language-dependent caches. Called when a player changes
     * their game language so that descriptions and names are re-resolved
     * in the new language on the next inventory packet.
     * <p>
     * Does <b>not</b> clear the structural {@code virtualItemCache} (item
     * definitions are language-independent) or per-player slot tracking.
     */
    public void clearLanguageCaches() {
        originalDescriptionCache.clear();
        originalNameCache.clear();
        builtDescriptionCache.clear();
    }

    public void clearCache() {
        virtualItemCache.clear();
        sentToPlayer.clear();
        playerSlotVirtualIds.clear();
        descriptionKeyCache.clear();
        originalDescriptionCache.clear();
        originalNameCache.clear();
        builtDescriptionCache.clear();
    }

    // ── Static helper for merging modifier maps ──

    /**
     * Merges two modifier maps. For shared keys, modifier arrays are concatenated.
     *
     * @param original   the original item's modifiers (may be null)
     * @param additional the modifiers to add
     * @return a new merged map
     */
    @Nonnull
    private static Map<Integer, Modifier[]> mergeModifierMaps(
            @Nullable Map<Integer, Modifier[]> original,
            @Nonnull Map<Integer, Modifier[]> additional) {

        if (original == null || original.isEmpty()) {
            return new HashMap<>(additional);
        }

        Map<Integer, Modifier[]> merged = new HashMap<>(original);
        for (Map.Entry<Integer, Modifier[]> entry : additional.entrySet()) {
            merged.merge(entry.getKey(), entry.getValue(), (a, b) -> {
                Modifier[] result = java.util.Arrays.copyOf(a, a.length + b.length);
                System.arraycopy(b, 0, result, a.length, b.length);
                return result;
            });
        }
        return merged;
    }
}
