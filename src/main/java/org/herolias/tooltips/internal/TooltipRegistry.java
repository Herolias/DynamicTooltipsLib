package org.herolias.tooltips.internal;

import com.hypixel.hytale.logger.HytaleLogger;
import org.herolias.tooltips.api.TooltipData;
import org.herolias.tooltips.api.TooltipProvider;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central registry that manages all {@link TooltipProvider}s and composes
 * their contributions into a final tooltip for each item.
 * <p>
 * <h2>Composition rules</h2>
 * Providers are queried in {@linkplain TooltipProvider#getPriority() priority}
 * order (ascending). The results are composed as follows:
 * <ul>
 *   <li><b>Description override</b>: if any provider returns a non-null
 *       {@link TooltipData#getDescriptionOverride()}, the highest-priority
 *       one wins and all additive lines are discarded.</li>
 *   <li><b>Name override</b>: if any provider returns a non-null
 *       {@link TooltipData#getNameOverride()}, the highest-priority one wins.</li>
 *   <li><b>Additive lines</b>: all providers' lines are concatenated in
 *       priority order, separated by newlines.</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * The provider list uses copy-on-write semantics via a volatile snapshot.
 * {@link #compose} is called from packet-processing threads and is lock-free.
 */
public class TooltipRegistry {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /**
     * Registered providers. Guarded by synchronizing on {@code providerLock}
     * for writes; reads use the volatile {@code providerSnapshot}.
     */
    private final Map<String, TooltipProvider> providers = new LinkedHashMap<>();
    private final Object providerLock = new Object();

    /** Sorted snapshot for lock-free reads during packet processing. */
    private volatile List<TooltipProvider> providerSnapshot = Collections.emptyList();

    /** Cache for composed descriptions: combinedHash → composed description. */
    private final ConcurrentHashMap<String, ComposedTooltip> composedCache = new ConcurrentHashMap<>();

    /**
     * Fast-path cache: "itemId\0metadata" → ComposedTooltip (or {@link #EMPTY_SENTINEL}).
     * <p>
     * This cache sits <em>above</em> the provider calls. If the exact same
     * (itemId, metadata) pair has been seen before, we return the cached result
     * without invoking any provider. This eliminates metadata parsing on every
     * outbound packet for items that haven't changed.
     * <p>
     * Bounded to {@link #STATE_CACHE_MAX} entries to prevent unbounded growth.
     */
    private final ConcurrentHashMap<String, ComposedTooltip> itemStateCache = new ConcurrentHashMap<>();

    /** Sentinel for items with no tooltip data (caches negative results). */
    private static final ComposedTooltip EMPTY_SENTINEL = new ComposedTooltip(
            Collections.emptyList(), null, null, "");

    /** Maximum entries in the item-state cache before new entries are rejected. */
    private static final int STATE_CACHE_MAX = 4096;

    // ─────────────────────────────────────────────────────────────────────
    //  Provider management
    // ─────────────────────────────────────────────────────────────────────

    public void registerProvider(@Nonnull TooltipProvider provider) {
        synchronized (providerLock) {
            providers.put(provider.getProviderId(), provider);
            rebuildSnapshot();
        }
        LOGGER.atInfo().log("Registered TooltipProvider: " + provider.getProviderId()
                + " (priority=" + provider.getPriority() + ")");
        composedCache.clear();
        itemStateCache.clear();
    }

    public boolean unregisterProvider(@Nonnull String providerId) {
        synchronized (providerLock) {
            TooltipProvider removed = providers.remove(providerId);
            if (removed == null) return false;
            rebuildSnapshot();
        }
        LOGGER.atInfo().log("Unregistered TooltipProvider: " + providerId);
        composedCache.clear();
        itemStateCache.clear();
        return true;
    }

    private void rebuildSnapshot() {
        List<TooltipProvider> sorted = new ArrayList<>(providers.values());
        sorted.sort(Comparator.comparingInt(TooltipProvider::getPriority));
        providerSnapshot = Collections.unmodifiableList(sorted);
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Tooltip composition
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Queries all registered providers for the given item and composes
     * their contributions.
     * <p>
     * Uses a two-level cache:
     * <ol>
     *   <li><b>Item-state cache</b> — keyed by {@code (itemId, metadata)}.
     *       If the exact same item state has been seen before, returns instantly
     *       without calling any provider.</li>
     *   <li><b>Composed cache</b> — keyed by combined hash. If different items
     *       happen to produce the same provider outputs, they share the composed
     *       result.</li>
     * </ol>
     *
     * @param itemId   the real item ID
     * @param metadata the item's metadata JSON, or null
     * @return a composed tooltip, or {@code null} if no provider has anything
     */
    @Nullable
    public ComposedTooltip compose(@Nonnull String itemId, @Nullable String metadata) {
        // ── Fast path: item-state cache ──
        String stateKey = metadata != null ? itemId + "\0" + metadata : itemId;
        ComposedTooltip stateCached = itemStateCache.get(stateKey);
        if (stateCached != null) {
            return stateCached == EMPTY_SENTINEL ? null : stateCached;
        }

        List<TooltipProvider> snapshot = providerSnapshot;
        if (snapshot.isEmpty()) return null;

        List<ProviderResult> results = null;

        for (TooltipProvider provider : snapshot) {
            try {
                TooltipData data = provider.getTooltipData(itemId, metadata);
                if (data != null && !data.isEmpty()) {
                    if (results == null) results = new ArrayList<>(snapshot.size());
                    results.add(new ProviderResult(provider, data));
                }
            } catch (Exception e) {
                LOGGER.atWarning().log("TooltipProvider '" + provider.getProviderId()
                        + "' threw exception for item '" + itemId + "': " + e.getMessage());
            }
        }

        if (results == null) {
            cacheItemState(stateKey, EMPTY_SENTINEL);
            return null;
        }

        // Build combined hash input for virtual ID generation
        StringBuilder hashBuilder = new StringBuilder();
        for (ProviderResult r : results) {
            hashBuilder.append(r.provider.getProviderId())
                    .append(':')
                    .append(r.data.getStableHashInput())
                    .append(';');
        }
        String combinedHashInput = hashBuilder.toString();
        String combinedHash = computeHash(combinedHashInput);

        // Check composed cache
        final List<ProviderResult> finalResults = results;
        ComposedTooltip result = composedCache.computeIfAbsent(combinedHash, h ->
                buildComposedTooltip(finalResults, combinedHash));

        cacheItemState(stateKey, result);
        return result;
    }

    private void cacheItemState(@Nonnull String stateKey, @Nonnull ComposedTooltip value) {
        if (itemStateCache.size() < STATE_CACHE_MAX) {
            itemStateCache.put(stateKey, value);
        }
    }

    /**
     * Builds the final composed tooltip from provider results.
     */
    @Nonnull
    private ComposedTooltip buildComposedTooltip(@Nonnull List<ProviderResult> results,
                                                  @Nonnull String combinedHash) {
        String nameOverride = null;
        String descriptionOverride = null;
        List<String> allLines = new ArrayList<>();

        // Results are already in priority order (ascending).
        // For overrides, higher priority (later in list) wins.
        for (ProviderResult r : results) {
            TooltipData data = r.data;

            if (data.getNameOverride() != null) {
                nameOverride = data.getNameOverride();
            }

            if (data.getDescriptionOverride() != null) {
                descriptionOverride = data.getDescriptionOverride();
            }

            allLines.addAll(data.getLines());
        }

        return new ComposedTooltip(
                Collections.unmodifiableList(allLines),
                nameOverride,
                descriptionOverride,
                combinedHash
        );
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Composed result
    // ─────────────────────────────────────────────────────────────────────

    /**
     * The final composed tooltip for one item, combining all providers' contributions.
     */
    public static final class ComposedTooltip {
        private final List<String> additiveLines;
        private final String nameOverride;
        private final String descriptionOverride;
        private final String combinedHash;

        ComposedTooltip(List<String> additiveLines,
                        @Nullable String nameOverride,
                        @Nullable String descriptionOverride,
                        @Nonnull String combinedHash) {
            this.additiveLines = additiveLines;
            this.nameOverride = nameOverride;
            this.descriptionOverride = descriptionOverride;
            this.combinedHash = combinedHash;
        }

        @Nonnull public List<String> getAdditiveLines() { return additiveLines; }
        @Nullable public String getNameOverride() { return nameOverride; }
        @Nullable public String getDescriptionOverride() { return descriptionOverride; }
        @Nonnull public String getCombinedHash() { return combinedHash; }

        /**
         * Builds the final description string by applying this composed tooltip
         * to the item's original description.
         *
         * @param originalDescription the item's original description text (may be null/empty)
         * @return the enriched description
         */
        @Nonnull
        public String buildDescription(@Nullable String originalDescription) {
            // Full description override takes absolute precedence
            if (descriptionOverride != null) {
                return descriptionOverride;
            }

            StringBuilder sb = new StringBuilder();

            if (originalDescription != null && !originalDescription.isEmpty()) {
                sb.append(originalDescription);
            }

            if (!additiveLines.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append("\n\n");
                }
                for (int i = 0; i < additiveLines.size(); i++) {
                    if (i > 0) sb.append('\n');
                    sb.append(additiveLines.get(i));
                }
            }

            return sb.toString();
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Internal helpers
    // ─────────────────────────────────────────────────────────────────────

    private static final class ProviderResult {
        final TooltipProvider provider;
        final TooltipData data;

        ProviderResult(TooltipProvider provider, TooltipData data) {
            this.provider = provider;
            this.data = data;
        }
    }

    /**
     * Computes a deterministic 8-character hex hash from the given input.
     */
    @Nonnull
    static String computeHash(@Nonnull String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (Exception e) {
            // Fallback: simple hashCode-based hash
            return String.format("%08x", input.hashCode());
        }
    }

    /**
     * Clears all caches. Safe to call on reload or when provider logic
     * changes (e.g. config reload in a consuming mod).
     */
    public void clearCache() {
        composedCache.clear();
        itemStateCache.clear();
    }
}
