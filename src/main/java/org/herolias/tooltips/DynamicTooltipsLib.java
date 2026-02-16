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

        // Register the public API
        DynamicTooltipsApi api = new DynamicTooltipsApiImpl(
                tooltipRegistry, virtualItemRegistry, packetAdapter);
        DynamicTooltipsApiProvider.register(api);

        // Register PlayerDisconnectEvent to clean up registry cache
        this.getEventRegistry().registerGlobal(com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent.class, this::onPlayerDisconnect);
        LOGGER.atInfo().log("Registered PlayerDisconnectEvent listener for cleanup");

        LOGGER.atInfo().log("DynamicTooltipsLib setup complete — API registered");
    }

    private void onPlayerDisconnect(com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent event) {
        // Clean up all data associated with this player
        if (packetAdapter != null) {
            packetAdapter.onPlayerLeave(event.getPlayerRef().getUuid());
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
