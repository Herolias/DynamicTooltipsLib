package org.herolias.tooltips;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import org.herolias.tooltips.api.DynamicTooltipsApi;
import org.herolias.tooltips.api.DynamicTooltipsApiProvider;
import org.herolias.tooltips.api.TooltipProvider;
import org.herolias.tooltips.internal.TooltipPacketAdapter;
import org.herolias.tooltips.internal.TooltipRegistry;
import org.herolias.tooltips.internal.VirtualItemRegistry;

import javax.annotation.Nonnull;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * DynamicTooltipsLib — a library mod that enables other mods to add
 * dynamic, per-item tooltips to Hytale's inventory system.
 * <p>
 * This plugin manages the lifecycle of the virtual item ID system and
 * the packet adapter that intercepts inventory packets. Other mods
 * interact with it exclusively through the {@link DynamicTooltipsApi}.
 */
public class DynamicTooltipsLib extends JavaPlugin {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private TooltipRegistry tooltipRegistry;
    private VirtualItemRegistry virtualItemRegistry;
    private TooltipPacketAdapter packetAdapter;

    /** Tracks known-online players so we can detect disconnects. */
    private final Set<UUID> knownPlayers = ConcurrentHashMap.newKeySet();

    /**
     * Tracks how many consecutive detector ticks each player has been "missing"
     * (not found in any world). This prevents false disconnect detection during
     * portal transitions, where a player is briefly between worlds.
     */
    private final ConcurrentHashMap<UUID, Integer> missedTicks = new ConcurrentHashMap<>();

    /**
     * Number of consecutive detector ticks (at 1 s interval) a player must be
     * absent from all worlds before we treat them as disconnected. This grace
     * period covers portal transitions, which can take several seconds.
     */
    private static final int DISCONNECT_GRACE_TICKS = 10;

    public DynamicTooltipsLib(@Nonnull JavaPluginInit init) {
        super(init);
        LOGGER.atInfo().log("DynamicTooltipsLib v"
                + this.getManifest().getVersion().toString() + " loading...");
    }

    @Override
    protected void setup() {
        LOGGER.atInfo().log("Setting up DynamicTooltipsLib...");

        // Initialize core components
        this.tooltipRegistry = new TooltipRegistry();
        this.virtualItemRegistry = new VirtualItemRegistry();
        this.packetAdapter = new TooltipPacketAdapter(virtualItemRegistry, tooltipRegistry);

        // Register the packet adapter (outbound + inbound filters)
        this.packetAdapter.register();
        LOGGER.atInfo().log("Registered TooltipPacketAdapter (outbound + inbound filters)");

        // Register the built-in custom-data tooltip provider
        this.tooltipRegistry.registerProvider(
                new org.herolias.tooltips.internal.CustomDataTooltipProvider());
        LOGGER.atInfo().log("Registered built-in CustomDataTooltipProvider");

        // Register the public API
        DynamicTooltipsApi api = new DynamicTooltipsApiImpl(
                tooltipRegistry, virtualItemRegistry, packetAdapter);
        DynamicTooltipsApiProvider.register(api);

        LOGGER.atInfo().log("DynamicTooltipsLib setup complete — API registered");
    }

    @Override
    protected void start() {
        // Schedule a lightweight disconnect-detector (same pattern as Simple Enchantments)
        try {
            HytaleServer.SCHEDULED_EXECUTOR.scheduleAtFixedRate(
                    this::checkForDisconnectedPlayers,
                    0, 1000, // 1 second — player leave cleanup is not latency-critical
                    TimeUnit.MILLISECONDS
            );
            LOGGER.atInfo().log("Scheduled player disconnect detector");
        } catch (Exception e) {
            LOGGER.atSevere().log("Failed to schedule disconnect detector: " + e.getMessage());
        }
        LOGGER.atInfo().log("DynamicTooltipsLib started");
    }

    /**
     * Polls the online player list and cleans up state for disconnected players.
     * This mirrors the Hytale modding pattern used by Simple Enchantments, since
     * there is no dedicated player-disconnect event in the Hytale API.
     * <p>
     * A grace period ({@link #DISCONNECT_GRACE_TICKS}) prevents false positives
     * during portal transitions, where a player is temporarily not present in
     * any world while the client loads the new instance.
     */
    private void checkForDisconnectedPlayers() {
        try {
            if (Universe.get() == null) return;

            Set<UUID> onlinePlayers = new HashSet<>();

            for (World world : Universe.get().getWorlds().values()) {
                if (world == null) continue;
                for (PlayerRef ref : world.getPlayerRefs()) {
                    if (ref != null && ref.isValid()) {
                        UUID uuid = ref.getUuid();
                        onlinePlayers.add(uuid);
                        knownPlayers.add(uuid);
                    }
                }
            }

            // Find disconnected players — with grace period for world transitions
            Iterator<UUID> it = knownPlayers.iterator();
            while (it.hasNext()) {
                UUID uuid = it.next();
                if (!onlinePlayers.contains(uuid)) {
                    int missed = missedTicks.merge(uuid, 1, Integer::sum);
                    if (missed >= DISCONNECT_GRACE_TICKS) {
                        it.remove();
                        missedTicks.remove(uuid);
                        packetAdapter.onPlayerLeave(uuid);
                        virtualItemRegistry.onPlayerLeave(uuid);
                    }
                } else {
                    // Player is back (or still online) — reset grace counter
                    missedTicks.remove(uuid);
                }
            }
        } catch (Exception e) {
            LOGGER.atWarning().log("Error in disconnect detector: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  API implementation (package-private inner class)
    // ─────────────────────────────────────────────────────────────────────

    private static class DynamicTooltipsApiImpl implements DynamicTooltipsApi {
        private final TooltipRegistry registry;
        private final VirtualItemRegistry virtualItemRegistry;
        private final TooltipPacketAdapter packetAdapter;

        DynamicTooltipsApiImpl(TooltipRegistry registry,
                               VirtualItemRegistry virtualItemRegistry,
                               TooltipPacketAdapter packetAdapter) {
            this.registry = registry;
            this.virtualItemRegistry = virtualItemRegistry;
            this.packetAdapter = packetAdapter;
        }

        @Override
        public void registerProvider(@Nonnull TooltipProvider provider) {
            registry.registerProvider(provider);
        }

        @Override
        public boolean unregisterProvider(@Nonnull String providerId) {
            return registry.unregisterProvider(providerId);
        }

        @Override
        public void invalidatePlayer(@Nonnull java.util.UUID playerUuid) {
            registry.clearCache();
            virtualItemRegistry.invalidatePlayer(playerUuid);
            packetAdapter.invalidatePlayer(playerUuid);
            LOGGER.atFine().log("Invalidated tooltip caches for player " + playerUuid);
        }

        @Override
        public void invalidateAll() {
            registry.clearCache();
            virtualItemRegistry.clearCache();
            packetAdapter.invalidateAllPlayers();
            LOGGER.atInfo().log("Invalidated all tooltip caches");
        }

        @Override
        public void refreshPlayer(@Nonnull java.util.UUID playerUuid) {
            invalidatePlayer(playerUuid);
            boolean sent = packetAdapter.refreshPlayer(playerUuid);
            LOGGER.atFine().log("Refresh player " + playerUuid + ": " + (sent ? "sent" : "no cached packet"));
        }

        @Override
        public void refreshAllPlayers() {
            invalidateAll();
            int count = packetAdapter.refreshAllPlayers();
            LOGGER.atInfo().log("Refreshed tooltips for " + count + " players");
        }
    }
}
