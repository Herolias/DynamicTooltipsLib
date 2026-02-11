package org.herolias.tooltips.internal;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.ItemBase;
import com.hypixel.hytale.protocol.ItemWithAllMetadata;
import com.hypixel.hytale.protocol.InventorySection;
import com.hypixel.hytale.protocol.Packet;
import com.hypixel.hytale.protocol.UpdateType;
import com.hypixel.hytale.protocol.packets.assets.UpdateItems;
import com.hypixel.hytale.protocol.packets.assets.UpdateTranslations;
import com.hypixel.hytale.protocol.packets.interaction.SyncInteractionChains;
import com.hypixel.hytale.protocol.packets.interaction.SyncInteractionChain;
import com.hypixel.hytale.protocol.packets.inventory.UpdatePlayerInventory;
import com.hypixel.hytale.protocol.packets.player.JoinWorld;
import com.hypixel.hytale.protocol.packets.player.MouseInteraction;
import com.hypixel.hytale.protocol.packets.window.OpenWindow;
import com.hypixel.hytale.protocol.packets.window.UpdateWindow;
import com.hypixel.hytale.protocol.packets.interface_.CustomPage;
import com.hypixel.hytale.protocol.packets.interface_.CustomUICommand;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.io.adapter.PacketAdapters;
import com.hypixel.hytale.server.core.io.adapter.PacketFilter;
import com.hypixel.hytale.server.core.io.adapter.PlayerPacketFilter;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import org.bson.BsonDocument;
import org.bson.BsonValue;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Bidirectional packet adapter that provides per-item dynamic tooltips
 * using <b>virtual item IDs</b> for all inventory sections.
 *
 * <h2>Problem</h2>
 * Hytale's tooltip system resolves item descriptions per item <em>type</em>.
 * Two items of the same type always share the same description key, making it
 * impossible to show different tooltips on two items of the same base type.
 *
 * <h2>Solution: virtual item IDs</h2>
 * Every item with tooltip data gets a unique virtual item ID (a clone of the
 * original with modified translation properties). This applies uniformly to
 * <b>all</b> inventory sections: hotbar, utility, tools, armor, storage,
 * backpack, builder material, and container windows.
 *
 * <h2>Inbound translation</h2>
 * The inbound filter translates any virtual item IDs in {@link MouseInteraction}
 * and {@link SyncInteractionChains} packets back to real IDs, ensuring that
 * interactions work correctly despite the virtual IDs used for display.
 *
 * <h2>Generalization</h2>
 * This adapter is <b>mod-agnostic</b>. It does not contain any enchantment-specific
 * logic. Instead, it delegates to a {@link TooltipRegistry} which queries all
 * registered {@link org.herolias.tooltips.api.TooltipProvider}s to compose the
 * final tooltip for each item.
 */
public class TooltipPacketAdapter {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final VirtualItemRegistry virtualItemRegistry;
    private final TooltipRegistry tooltipRegistry;

    /** Registered outbound filter handle. */
    private PacketFilter outboundFilter;
    /** Registered inbound filter handle. */
    private PacketFilter inboundFilter;

    /**
     * Re-entrancy guard. Set to {@code true} while the outbound filter is
     * processing a packet and sending auxiliary packets via {@code writeNoCache()}.
     */
    private final ThreadLocal<Boolean> isProcessing = ThreadLocal.withInitial(() -> false);


    /**
     * Per-player tracking of last sent translations (for diff-based sending).
     */
    private final ConcurrentHashMap<UUID, Map<String, String>> lastSentTranslations = new ConcurrentHashMap<>();

    /**
     * Per-player cache of the last <em>unprocessed</em> inventory packet.
     * Deep-cloned before any tooltip processing modifies it.
     * Used by {@link #refreshPlayer} to replay the packet with fresh data.
     */
    private final ConcurrentHashMap<UUID, UpdatePlayerInventory> lastRawInventory = new ConcurrentHashMap<>();

    /**
     * Per-player cached {@link PlayerRef}, updated on every outbound packet.
     * Used by {@link #refreshPlayer} and {@link #refreshAllPlayers}.
     */
    private final ConcurrentHashMap<UUID, PlayerRef> knownPlayerRefs = new ConcurrentHashMap<>();

    /**
     * Players currently transitioning between worlds. Set when a {@link JoinWorld}
     * packet is detected outbound; cleared when the first {@link UpdatePlayerInventory}
     * arrives for that player. While set, tooltip processing is deferred to avoid
     * injecting auxiliary packets that delay the client's {@code ClientReady} response
     * past the portal instance world's timeout.
     */
    private final Set<UUID> worldTransitioning = ConcurrentHashMap.newKeySet();

    /** Delay (in seconds) before replaying inventory with tooltips after a world transition. */
    private static final int POST_TRANSITION_REFRESH_DELAY_SECS = 2;

    public TooltipPacketAdapter(
            @Nonnull VirtualItemRegistry virtualItemRegistry,
            @Nonnull TooltipRegistry tooltipRegistry) {
        this.virtualItemRegistry = virtualItemRegistry;
        this.tooltipRegistry = tooltipRegistry;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Registration / deregistration
    // ═══════════════════════════════════════════════════════════════════════

    public void register() {
        outboundFilter = PacketAdapters.registerOutbound((PlayerPacketFilter) this::onOutboundPacket);
        inboundFilter = PacketAdapters.registerInbound((PlayerPacketFilter) this::onInboundPacket);
        LOGGER.atInfo().log("TooltipPacketAdapter registered (outbound + inbound filters)");
    }

    public void deregister() {
        if (outboundFilter != null) {
            try { PacketAdapters.deregisterOutbound(outboundFilter); } catch (Exception e) {
                LOGGER.atWarning().log("Failed to deregister outbound filter: " + e.getMessage());
            }
            outboundFilter = null;
        }
        if (inboundFilter != null) {
            try { PacketAdapters.deregisterInbound(inboundFilter); } catch (Exception e) {
                LOGGER.atWarning().log("Failed to deregister inbound filter: " + e.getMessage());
            }
            inboundFilter = null;
        }
    }

    public void onPlayerLeave(@Nonnull UUID playerUuid) {
        worldTransitioning.remove(playerUuid);
        lastSentTranslations.remove(playerUuid);
        lastRawInventory.remove(playerUuid);
        knownPlayerRefs.remove(playerUuid);
    }

    /**
     * Schedules a deferred tooltip refresh for a player who just transitioned
     * between worlds. The delay gives the client time to finish the world load
     * and send {@code ClientReady} before we inject auxiliary tooltip packets.
     */
    private void schedulePostTransitionRefresh(@Nonnull UUID playerUuid) {
        try {
            HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
                try {
                    refreshPlayer(playerUuid);
                } catch (Exception e) {
                    LOGGER.atWarning().log("Post-transition tooltip refresh failed for "
                            + playerUuid + ": " + e.getMessage());
                }
            }, POST_TRANSITION_REFRESH_DELAY_SECS, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOGGER.atWarning().log("Failed to schedule post-transition refresh for "
                    + playerUuid + ": " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  INBOUND filter  (client → server)
    // ═══════════════════════════════════════════════════════════════════════

    private boolean onInboundPacket(@Nonnull PlayerRef playerRef, @Nonnull Packet packet) {
        try {
            if (packet instanceof MouseInteraction mousePacket) {
                translateMouseInteraction(mousePacket);
            } else if (packet instanceof SyncInteractionChains syncPacket) {
                translateInboundSyncInteractionChains(syncPacket);
            }
        } catch (Exception e) {
            LOGGER.atWarning().log("Error in inbound packet adapter for "
                    + playerRef.getUuid() + ": " + e.getMessage());
        }
        return false;
    }

    private void translateMouseInteraction(@Nonnull MouseInteraction packet) {
        if (packet.itemInHandId != null && VirtualItemRegistry.isVirtualId(packet.itemInHandId)) {
            String baseId = VirtualItemRegistry.getBaseItemId(packet.itemInHandId);
            if (baseId != null) packet.itemInHandId = baseId;
        }
    }

    private void translateInboundSyncInteractionChains(@Nonnull SyncInteractionChains syncPacket) {
        for (SyncInteractionChain chain : syncPacket.updates) {
            translateInboundChainItemIds(chain);
        }
    }

    private void translateInboundChainItemIds(@Nonnull SyncInteractionChain chain) {
        if (chain.itemInHandId != null && VirtualItemRegistry.isVirtualId(chain.itemInHandId)) {
            String baseId = VirtualItemRegistry.getBaseItemId(chain.itemInHandId);
            if (baseId != null) chain.itemInHandId = baseId;
        }
        if (chain.utilityItemId != null && VirtualItemRegistry.isVirtualId(chain.utilityItemId)) {
            String baseId = VirtualItemRegistry.getBaseItemId(chain.utilityItemId);
            if (baseId != null) chain.utilityItemId = baseId;
        }
        if (chain.toolsItemId != null && VirtualItemRegistry.isVirtualId(chain.toolsItemId)) {
            String baseId = VirtualItemRegistry.getBaseItemId(chain.toolsItemId);
            if (baseId != null) chain.toolsItemId = baseId;
        }
        if (chain.newForks != null) {
            for (SyncInteractionChain fork : chain.newForks) {
                if (fork != null) translateInboundChainItemIds(fork);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  OUTBOUND filter  (server → client)
    // ═══════════════════════════════════════════════════════════════════════

    private boolean onOutboundPacket(@Nonnull PlayerRef playerRef, @Nonnull Packet packet) {
        if (isProcessing.get()) return false;

        isProcessing.set(true);
        try {
            UUID playerUuid = playerRef.getUuid();

            // Track the PlayerRef for refresh support
            knownPlayerRefs.put(playerUuid, playerRef);

            // ── World-transition detection ──
            // When a JoinWorld packet is sent, the client must process it and
            // respond with ClientReady before the portal instance world's
            // timeout expires. Injecting auxiliary packets (UpdateItems,
            // UpdateTranslations) during this window can delay ClientReady
            // past the timeout, causing the world thread to shut down and
            // the player to be disconnected. We mark the player as
            // "transitioning" and defer tooltip processing until afterwards.
            if (packet instanceof JoinWorld) {
                worldTransitioning.add(playerUuid);
            } else if (packet instanceof UpdatePlayerInventory invPacket) {
                // Cache a deep clone of the raw packet BEFORE processing modifies it
                lastRawInventory.put(playerUuid, deepCloneInventory(invPacket));

                if (worldTransitioning.remove(playerUuid)) {
                    // Player is mid-transition — let the vanilla inventory packet
                    // through unmodified so the client can send ClientReady ASAP.
                    // Tooltips will be applied by a deferred refresh.
                    schedulePostTransitionRefresh(playerUuid);
                } else {
                    processPlayerInventory(playerRef, invPacket);
                }
            } else if (packet instanceof OpenWindow openWindow) {
                processWindowInventory(playerRef, openWindow.inventory);
            } else if (packet instanceof UpdateWindow updateWindow) {
                processWindowInventory(playerRef, updateWindow.inventory);
            } else if (packet instanceof CustomPage customPage) {
                processCustomPage(playerRef, customPage);
            }
        } catch (Exception e) {
            LOGGER.atWarning().log("Error in outbound packet adapter for "
                    + playerRef.getUuid() + ": " + e.getMessage());
        } finally {
            isProcessing.set(false);
        }

        return false;
    }

    // ───────────────────────────────────────────────────────────────────────
    //  Player inventory processing
    // ───────────────────────────────────────────────────────────────────────

    private void processPlayerInventory(@Nonnull PlayerRef playerRef,
                                        @Nonnull UpdatePlayerInventory packet) {
        UUID playerUuid = playerRef.getUuid();
        String language = playerRef.getLanguage();

        Map<String, ItemBase> newVirtualItems = new LinkedHashMap<>();
        Map<String, String> translations = new LinkedHashMap<>();

        try {
            // All sections use virtual item IDs uniformly.
            // The inbound filter translates virtual IDs back to real IDs
            // for SyncInteractionChains and MouseInteraction packets.
            processSection(playerUuid, "hotbar", packet.hotbar, language, newVirtualItems, translations);
            processSection(playerUuid, "utility", packet.utility, language, newVirtualItems, translations);
            processSection(playerUuid, "tools", packet.tools, language, newVirtualItems, translations);
            processSection(playerUuid, "armor", packet.armor, language, newVirtualItems, translations);
            processSection(playerUuid, "storage", packet.storage, language, newVirtualItems, translations);
            processSection(playerUuid, "backpack", packet.backpack, language, newVirtualItems, translations);
            processSection(playerUuid, "builderMaterial", packet.builderMaterial, language, newVirtualItems, translations);
        } catch (Exception e) {
            LOGGER.atSevere().log("Error in processPlayerInventory for " + playerUuid + ": " + e.getMessage());
        } finally {
            sendAuxiliaryPackets(playerRef, newVirtualItems, translations);
        }
    }

    // ───────────────────────────────────────────────────────────────────────
    //  Window (chest/container) inventory processing
    // ───────────────────────────────────────────────────────────────────────

    private void processWindowInventory(@Nonnull PlayerRef playerRef,
                                        @Nullable InventorySection section) {
        if (section == null) return;

        UUID playerUuid = playerRef.getUuid();
        String language = playerRef.getLanguage();

        Map<String, ItemBase> newVirtualItems = new LinkedHashMap<>();
        Map<String, String> translations = new LinkedHashMap<>();

        processSection(playerUuid, null, section, language, newVirtualItems, translations);
        sendAuxiliaryPackets(playerRef, newVirtualItems, translations);
    }

    // ───────────────────────────────────────────────────────────────────────
    //  CustomUI (CustomPage) processing
    // ───────────────────────────────────────────────────────────────────────

    private void processCustomPage(@Nonnull PlayerRef playerRef,
                                   @Nonnull CustomPage customPage) {
        if (customPage.commands == null || customPage.commands.length == 0) return;

        UUID playerUuid = playerRef.getUuid();
        String language = playerRef.getLanguage();

        Map<String, ItemBase> newVirtualItems = new LinkedHashMap<>();
        Map<String, String> translations = new LinkedHashMap<>();

        for (CustomUICommand command : customPage.commands) {
            if (command.data == null || command.data.isEmpty()) continue;

            String modifiedData = processCustomUICommandData(
                    playerUuid, language, command.data, newVirtualItems, translations);
            if (modifiedData != null) {
                command.data = modifiedData;
            }
        }

        sendAuxiliaryPackets(playerRef, newVirtualItems, translations);
    }

    @Nullable
    private String processCustomUICommandData(
            @Nonnull UUID playerUuid,
            @Nullable String language,
            @Nonnull String data,
            @Nonnull Map<String, ItemBase> newVirtualItems,
            @Nonnull Map<String, String> translations) {

        try {
            BsonDocument doc = BsonDocument.parse(data);
            BsonValue value = doc.get("0");
            if (value == null) return null;

            boolean modified = false;

            if (value.isString()) {
                String potentialItemId = value.asString().getValue();
                if (!VirtualItemRegistry.isVirtualId(potentialItemId)) {
                    String virtualId = findVirtualIdForItem(playerUuid, potentialItemId, language,
                            newVirtualItems, translations);
                    if (virtualId != null) {
                        doc.put("0", new org.bson.BsonString(virtualId));
                        modified = true;
                    }
                }
            } else if (value.isArray()) {
                org.bson.BsonArray array = value.asArray();
                for (int i = 0; i < array.size(); i++) {
                    BsonValue element = array.get(i);
                    if (element.isDocument()) {
                        if (processItemGridSlotDocument(playerUuid, language,
                                element.asDocument(), newVirtualItems, translations)) {
                            modified = true;
                        }
                    }
                }
            }

            return modified ? doc.toJson() : null;

        } catch (Exception e) {
            LOGGER.atFine().log("Could not process CustomUICommand data: " + e.getMessage());
        }

        return null;
    }

    private boolean processItemGridSlotDocument(
            @Nonnull UUID playerUuid,
            @Nullable String language,
            @Nonnull BsonDocument slotDoc,
            @Nonnull Map<String, ItemBase> newVirtualItems,
            @Nonnull Map<String, String> translations) {

        BsonValue itemStackValue = slotDoc.get("ItemStack");
        if (itemStackValue == null || !itemStackValue.isDocument()) return false;

        BsonDocument itemStackDoc = itemStackValue.asDocument();
        BsonValue itemIdValue = itemStackDoc.get("ItemId");
        if (itemIdValue == null || !itemIdValue.isString()) return false;

        String itemId = itemIdValue.asString().getValue();
        if (VirtualItemRegistry.isVirtualId(itemId)) return false;

        String virtualId = findVirtualIdForItem(playerUuid, itemId, language,
                newVirtualItems, translations);

        if (virtualId != null) {
            itemStackDoc.put("ItemId", new org.bson.BsonString(virtualId));
            return true;
        }

        return false;
    }

    @Nullable
    private String findVirtualIdForItem(
            @Nonnull UUID playerUuid,
            @Nonnull String itemId,
            @Nullable String language,
            @Nonnull Map<String, ItemBase> newVirtualItems,
            @Nonnull Map<String, String> translations) {

        String virtualId = virtualItemRegistry.findVirtualIdForBaseItem(playerUuid, itemId);

        if (virtualId != null) {
            // Check if we have a cached composed tooltip for this virtual ID
            String descKey = VirtualItemRegistry.getVirtualDescriptionKey(virtualId);
            String cachedDesc = virtualItemRegistry.getCachedDescription(virtualId);

            if (cachedDesc != null) {
                // The virtual item base is already cached with the correct name override
                // from when it was first created via processSection.
                ItemBase virtualBase = virtualItemRegistry.getOrCreateVirtualItemBase(
                        itemId, virtualId, null);
                if (virtualBase != null) {
                    newVirtualItems.put(virtualId, virtualBase);
                    translations.put(descKey, cachedDesc);
                }
                return virtualId;
            }
        }

        return null;
    }

    // ───────────────────────────────────────────────────────────────────────
    //  Core section processing (shared by player inventory & containers)
    // ───────────────────────────────────────────────────────────────────────

    /**
     * Processes a single display-only {@link InventorySection}: for each item with
     * tooltip data, clones its {@link ItemWithAllMetadata}, sets the virtual ID on
     * the clone, and replaces the entry in the section's items map.
     */
    private void processSection(
            @Nonnull UUID playerUuid,
            @Nullable String sectionName,
            @Nullable InventorySection section,
            @Nullable String language,
            @Nonnull Map<String, ItemBase> newVirtualItems,
            @Nonnull Map<String, String> translations) {

        if (section == null || section.items == null || section.items.isEmpty()) return;

        for (Map.Entry<Integer, ItemWithAllMetadata> entry : section.items.entrySet()) {
            int slot = entry.getKey();
            ItemWithAllMetadata itemPacket = entry.getValue();

            if (itemPacket == null || itemPacket.itemId == null || itemPacket.itemId.isEmpty()) {
                if (sectionName != null) {
                    virtualItemRegistry.trackSlotVirtualId(playerUuid, sectionName + ":" + slot, null);
                }
                continue;
            }

            if (VirtualItemRegistry.isVirtualId(itemPacket.itemId)) continue;

            // Query all tooltip providers via the registry
            TooltipRegistry.ComposedTooltip composed = tooltipRegistry.compose(
                    itemPacket.itemId, itemPacket.metadata);

            if (composed == null) {
                if (sectionName != null) {
                    virtualItemRegistry.trackSlotVirtualId(playerUuid, sectionName + ":" + slot, null);
                }
                continue;
            }

            String baseItemId = itemPacket.itemId;
            String virtualId = virtualItemRegistry.generateVirtualId(baseItemId, composed.getCombinedHash());

            // Get or create the virtual ItemBase definition
            ItemBase virtualBase = virtualItemRegistry.getOrCreateVirtualItemBase(
                    baseItemId, virtualId, composed.getNameOverride());
            if (virtualBase == null) {
                if (sectionName != null) {
                    virtualItemRegistry.trackSlotVirtualId(playerUuid, sectionName + ":" + slot, null);
                }
                continue;
            }

            newVirtualItems.put(virtualId, virtualBase);

            // Build the description for this virtual item
            String descKey = VirtualItemRegistry.getVirtualDescriptionKey(virtualId);
            if (!translations.containsKey(descKey)) {
                String originalDesc = virtualItemRegistry.getOriginalDescription(baseItemId, language);
                String enrichedDesc = composed.buildDescription(originalDesc);
                translations.put(descKey, enrichedDesc);
                virtualItemRegistry.cacheDescription(virtualId, enrichedDesc);
            }

            // Handle name override translation
            if (composed.getNameOverride() != null) {
                String nameKey = VirtualItemRegistry.getVirtualNameKey(virtualId);
                translations.put(nameKey, composed.getNameOverride());
            }

            // Clone, swap ID, replace in section
            ItemWithAllMetadata clonedItem = itemPacket.clone();
            clonedItem.itemId = virtualId;
            section.items.put(slot, clonedItem);

            if (sectionName != null) {
                virtualItemRegistry.trackSlotVirtualId(playerUuid, sectionName + ":" + slot, virtualId);
            }
        }
    }



    // ═══════════════════════════════════════════════════════════════════════
    //  Auxiliary packet sending
    // ═══════════════════════════════════════════════════════════════════════

    public void sendAuxiliaryPackets(@Nonnull PlayerRef playerRef,
                                     @Nonnull Map<String, ItemBase> newVirtualItems,
                                     @Nonnull Map<String, String> translations) {
        if (newVirtualItems.isEmpty() && translations.isEmpty()) return;

        UUID playerUuid = playerRef.getUuid();

        // Send virtual item definitions the player hasn't seen yet
        Set<String> unsentItems = virtualItemRegistry.markAndGetUnsent(
                playerUuid, newVirtualItems.keySet());
        if (!unsentItems.isEmpty()) {
            Map<String, ItemBase> toSend = new LinkedHashMap<>();
            for (String vId : unsentItems) {
                ItemBase base = newVirtualItems.get(vId);
                if (base != null) toSend.put(vId, base);
            }
            if (!toSend.isEmpty()) {
                sendUpdateItems(playerRef, toSend);
            }
        }

        // Send translations — only if they differ from what was last sent
        if (!translations.isEmpty()) {
            Map<String, String> lastSent = lastSentTranslations.get(playerUuid);
            Map<String, String> delta = computeTranslationDelta(lastSent, translations);
            if (!delta.isEmpty()) {
                sendTranslations(playerRef, delta);
                if (lastSent == null) {
                    lastSentTranslations.put(playerUuid, new ConcurrentHashMap<>(delta));
                } else {
                    lastSent.putAll(delta);
                }
            }
        }
    }

    private void sendUpdateItems(@Nonnull PlayerRef playerRef,
                                 @Nonnull Map<String, ItemBase> items) {
        try {
            UpdateItems packet = new UpdateItems();
            packet.type = UpdateType.AddOrUpdate;
            packet.items = items;
            packet.removedItems = new String[0];
            // Virtual items share the exact same model and icon assets as their
            // base items — only the ID and translation properties differ. Skipping
            // model/icon reloading avoids a costly client-side stall, especially
            // during world transitions and first-time container opens.
            packet.updateModels = false;
            packet.updateIcons = false;
            playerRef.getPacketHandler().writeNoCache(packet);
        } catch (Exception e) {
            LOGGER.atWarning().log("Failed to send UpdateItems for virtual items: " + e.getMessage());
        }
    }

    private void sendTranslations(@Nonnull PlayerRef playerRef,
                                  @Nonnull Map<String, String> translations) {
        try {
            UpdateTranslations packet = new UpdateTranslations(UpdateType.AddOrUpdate, translations);
            playerRef.getPacketHandler().writeNoCache(packet);
        } catch (Exception e) {
            LOGGER.atWarning().log("Failed to send UpdateTranslations for virtual items: " + e.getMessage());
        }
    }



    // ─────────────────────────────────────────────────────────────────────────
    //  Helper: compute translation diff
    // ─────────────────────────────────────────────────────────────────────────

    @Nonnull
    private Map<String, String> computeTranslationDelta(
            @Nullable Map<String, String> lastSent,
            @Nonnull Map<String, String> current) {
        if (lastSent == null || lastSent.isEmpty()) return current;
        Map<String, String> delta = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : current.entrySet()) {
            String oldValue = lastSent.get(entry.getKey());
            if (!entry.getValue().equals(oldValue)) {
                delta.put(entry.getKey(), entry.getValue());
            }
        }
        return delta;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Invalidation & refresh support
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Clears all per-player packet-adapter caches for the given player.
     * The next outbound inventory packet will be fully reprocessed.
     */
    public void invalidatePlayer(@Nonnull UUID playerUuid) {
        lastSentTranslations.remove(playerUuid);
        // Note: we intentionally keep lastRawInventory and knownPlayerRefs —
        // they are needed for a subsequent refreshPlayer call.
    }

    /**
     * Clears all per-player caches for <b>every</b> tracked player.
     */
    public void invalidateAllPlayers() {
        lastSentTranslations.clear();
    }

    /**
     * Replays the last known inventory packet for a player, triggering a
     * full recomposition with fresh provider data.
     *
     * @param playerUuid the player to refresh
     * @return {@code true} if a refresh packet was sent
     */
    public boolean refreshPlayer(@Nonnull UUID playerUuid) {
        PlayerRef playerRef = knownPlayerRefs.get(playerUuid);
        if (playerRef == null || !playerRef.isValid()) return false;

        UpdatePlayerInventory rawPacket = lastRawInventory.get(playerUuid);
        if (rawPacket == null) return false;

        // Clone again so processing doesn't destroy the cached copy
        UpdatePlayerInventory clone = deepCloneInventory(rawPacket);

        try {
            // writeNoCache triggers the outbound filter when called outside
            // of isProcessing context, which causes full reprocessing.
            playerRef.getPacketHandler().writeNoCache(clone);
            return true;
        } catch (Exception e) {
            LOGGER.atWarning().log("Failed to send refresh packet for " + playerUuid + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Refreshes every known online player.
     *
     * @return the number of players successfully refreshed
     */
    public int refreshAllPlayers() {
        int count = 0;
        for (UUID uuid : knownPlayerRefs.keySet()) {
            if (refreshPlayer(uuid)) count++;
        }
        return count;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Deep clone helpers
    // ─────────────────────────────────────────────────────────────────────────

    @Nonnull
    private static UpdatePlayerInventory deepCloneInventory(@Nonnull UpdatePlayerInventory original) {
        UpdatePlayerInventory clone = new UpdatePlayerInventory();
        clone.hotbar = cloneSection(original.hotbar);
        clone.utility = cloneSection(original.utility);
        clone.tools = cloneSection(original.tools);
        clone.armor = cloneSection(original.armor);
        clone.storage = cloneSection(original.storage);
        clone.backpack = cloneSection(original.backpack);
        clone.builderMaterial = cloneSection(original.builderMaterial);
        return clone;
    }

    @Nullable
    private static InventorySection cloneSection(@Nullable InventorySection section) {
        if (section == null) return null;
        InventorySection clone = new InventorySection();
        clone.capacity = section.capacity;
        if (section.items != null) {
            clone.items = new HashMap<>();
            for (Map.Entry<Integer, ItemWithAllMetadata> entry : section.items.entrySet()) {
                clone.items.put(entry.getKey(),
                        entry.getValue() != null ? entry.getValue().clone() : null);
            }
        }
        return clone;
    }
}
