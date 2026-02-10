package org.herolias.tooltips.api;

import javax.annotation.Nonnull;

/**
 * Public API for the DynamicTooltipsLib library.
 * <p>
 * Other mods use this to register their {@link TooltipProvider}s, which
 * contribute dynamic tooltip content to items.
 * <p>
 * Obtain an instance via {@link DynamicTooltipsApiProvider#get()}.
 *
 * <pre>{@code
 * DynamicTooltipsApi api = DynamicTooltipsApiProvider.get();
 * if (api != null) {
 *     api.registerProvider(new MyTooltipProvider());
 * }
 * }</pre>
 */
public interface DynamicTooltipsApi {

    /**
     * Registers a tooltip provider.
     * <p>
     * If a provider with the same {@link TooltipProvider#getProviderId() ID}
     * is already registered, it is replaced.
     *
     * @param provider the provider to register
     */
    void registerProvider(@Nonnull TooltipProvider provider);

    /**
     * Unregisters a tooltip provider by its ID.
     *
     * @param providerId the provider's unique ID
     * @return {@code true} if a provider was removed, {@code false} if not found
     */
    boolean unregisterProvider(@Nonnull String providerId);

    // ─────────────────────────────────────────────────────────────────────
    //  Cache invalidation
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Invalidates all cached tooltip data for a specific player.
     * <p>
     * The next outbound inventory packet for this player will trigger a full
     * re-composition from all providers — no cached data is reused. Use this
     * after modifying item metadata that affects tooltips for a single player.
     *
     * @param playerUuid the player whose caches should be cleared
     */
    void invalidatePlayer(@Nonnull java.util.UUID playerUuid);

    /**
     * Invalidates <b>all</b> cached tooltip data (global + every player).
     * <p>
     * Use this when provider logic changes (e.g. after a config reload) so that
     * every future packet is recomposed with fresh data.
     */
    void invalidateAll();

    /**
     * Invalidates and immediately refreshes tooltips for a specific player.
     * <p>
     * This clears all caches for the player and replays the last known inventory
     * packet, causing an immediate tooltip update on the client. Use this when
     * you need the player to see updated tooltips <em>right now</em>, without
     * waiting for the next natural inventory packet.
     *
     * @param playerUuid the player to refresh
     */
    void refreshPlayer(@Nonnull java.util.UUID playerUuid);

    /**
     * Invalidates all caches and immediately refreshes tooltips for every
     * online player.
     * <p>
     * Equivalent to calling {@link #invalidateAll()} followed by
     * {@link #refreshPlayer(java.util.UUID)} for every online player.
     * Use after config reloads or provider logic changes that affect all players.
     */
    void refreshAllPlayers();
}
