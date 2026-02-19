package org.herolias.tooltips.api;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Interface that mods implement to contribute dynamic tooltip content to items.
 * <p>
 * Each mod registers one or more {@code TooltipProvider}s with the
 * {@link DynamicTooltipsApi}. When an inventory packet is sent to a player,
 * the library queries all registered providers for each item and composes
 * the results into a final tooltip.
 *
 * <h2>Multi-mod composition</h2>
 * <ul>
 *   <li><b>Additive lines</b> are appended in {@linkplain #getPriority() priority}
 *       order (lower priority first). Each provider's lines are kept separate.</li>
 *   <li><b>Name overrides</b> and <b>description overrides</b> are
 *       <em>destructive</em>: the highest-priority provider's value wins.
 *       Use sparingly and document conflicts with other mods.</li>
 * </ul>
 *
 * <h2>Performance</h2>
 * {@link #getTooltipData} is called on every outbound inventory packet for
 * every item. Implementations should be fast and avoid blocking I/O.
 * Return {@code null} for items this provider does not care about.
 */
public interface TooltipProvider {

    /**
     * Unique identifier for this provider (e.g. {@code "simple_enchantments"}).
     * Used for logging, conflict detection, and {@link DynamicTooltipsApi#unregisterProvider}.
     */
    @Nonnull
    String getProviderId();

    /**
     * Rendering priority. Lower values are rendered first (closer to the
     * original description). Higher values are rendered last.
     * <p>
     * Default providers should use {@code 100}. Use values below 100
     * to appear before default providers, or above 100 to appear after.
     */
    int getPriority();

    /**
     * Returns tooltip data for the given item, or {@code null} if this
     * provider has nothing to contribute.
     * <p>
     * Called during outbound packet processing for every item in every
     * inventory section. The implementation must be thread-safe and fast.
     *
     * @param itemId   the real (base) item ID (e.g. {@code "Tool_Pickaxe_Adamantite"})
     * @param metadata the item's metadata JSON string (from {@code ItemStack.metadata}),
     *                 or {@code null} if the item has no metadata
     * @return a {@link TooltipData} describing this provider's contribution, or
     *         {@code null} to indicate no tooltip modification
     */
    @Nullable
    TooltipData getTooltipData(@Nonnull String itemId, @Nullable String metadata);

    /**
     * Locale-aware variant of {@link #getTooltipData(String, String)}.
     * <p>
     * Override this method to produce locale-specific tooltip content (e.g.
     * translated additive lines). The default implementation delegates to
     * the locale-less overload, so existing providers are unaffected.
     *
     * @param itemId   the real (base) item ID
     * @param metadata the item's metadata JSON string, or {@code null}
     * @param locale   the player's language code (e.g. {@code "en-US"}, {@code "de-DE"}),
     *                 or {@code null} if unknown
     * @return a {@link TooltipData}, or {@code null} to indicate no tooltip modification
     */
    @Nullable
    default TooltipData getTooltipData(@Nonnull String itemId, @Nullable String metadata,
                                       @Nullable String locale) {
        return getTooltipData(itemId, metadata);
    }
}
