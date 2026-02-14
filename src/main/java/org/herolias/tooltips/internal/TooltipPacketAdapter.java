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
import com.hypixel.hytale.protocol.packets.inventory.SetActiveSlot;
import com.hypixel.hytale.protocol.packets.interface_.CustomPage;
import com.hypixel.hytale.protocol.packets.interface_.CustomUICommand;
import com.hypixel.hytale.protocol.packets.interface_.Notification;
import com.hypixel.hytale.protocol.FormattedMessage;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.io.adapter.PacketAdapters;
import com.hypixel.hytale.server.core.io.adapter.PacketFilter;
import com.hypixel.hytale.server.core.io.adapter.PlayerPacketFilter;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import org.bson.BsonDocument;
import org.bson.BsonValue;

import com.hypixel.hytale.protocol.packets.entities.EntityUpdates;
import com.hypixel.hytale.protocol.EntityUpdate;
import com.hypixel.hytale.protocol.ComponentUpdate;
import com.hypixel.hytale.protocol.ComponentUpdateType;
import com.hypixel.hytale.protocol.Equipment;
import com.hypixel.hytale.protocol.packets.player.SetClientId;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

// Additional imports for EntityStore access
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;

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

    /**
     * Map of Player UUID -> Entity ID (from SetClientId).
     * Used to identify EntityUpdates that target the local player.
     */
    private final ConcurrentHashMap<UUID, Integer> playerEntityIds = new ConcurrentHashMap<>();

    /**
     * Map of Player UUID -> Active Hotbar Slot Index (0-8).
     * Tracked from inbound SyncInteractionChain packets.
     */
    private final ConcurrentHashMap<UUID, Integer> playerActiveHotbarSlots = new ConcurrentHashMap<>();

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
        playerEntityIds.remove(playerUuid);
        playerActiveHotbarSlots.remove(playerUuid);
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
                translateInboundSyncInteractionChains(playerRef, syncPacket);
            } else if (packet instanceof SetActiveSlot setSlot) {
                // Track hotbar slot changes only (Inventory.HOTBAR_SECTION_ID = -1).
                if (setSlot.inventorySectionId == -1) {
                    playerActiveHotbarSlots.put(playerRef.getUuid(), setSlot.activeSlot);
                }
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

    private void translateInboundSyncInteractionChains(@Nonnull PlayerRef playerRef, @Nonnull SyncInteractionChains syncPacket) {
        for (SyncInteractionChain chain : syncPacket.updates) {
            // Track the player's active hotbar slot so we can use it to look up
            // the correct virtual item ID when processing outbound EntityUpdates.
            if (chain.activeHotbarSlot >= 0) {
                 playerActiveHotbarSlots.put(playerRef.getUuid(), chain.activeHotbarSlot);
            }
            if (chain.data != null && chain.data.targetSlot >= 0) {
                 playerActiveHotbarSlots.put(playerRef.getUuid(), chain.data.targetSlot);
            }
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
            } else if (packet instanceof SetClientId setId) {
                // Track the local player's entity ID
                playerEntityIds.put(playerUuid, setId.clientId);
            } else if (packet instanceof EntityUpdates updates) {
                // Intercept entity updates to ensure held items use virtual IDs
                processEntityUpdates(playerRef, updates);
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
            } else if (packet instanceof Notification) {
                // ─────────────────────────────────────────────────────────────────────
                //  Pickup Notifications (Dropped Item Name Fix)
                // ─────────────────────────────────────────────────────────────────────
                Notification notification = (Notification) packet;
                // Target the "picked up item" notification
                if (notification.message != null && "server.general.pickedUpItem".equals(notification.message.messageId)
                        && notification.item != null) {

                    String itemId = notification.item.itemId;
                    String metadata = notification.item.metadata;

                    // If the notification already has a virtual ID (e.g. from a previous update),
                    // we should look up the base ID to correctly resolve the tooltip.
                    if (VirtualItemRegistry.isVirtualId(itemId)) {
                        String baseId = VirtualItemRegistry.getBaseItemId(itemId);
                        if (baseId != null) {
                            itemId = baseId;
                        }
                    }

                    // We need to resolve the virtual item based on the item in the notification
                    TooltipRegistry.ComposedTooltip composed = tooltipRegistry.compose(itemId, metadata);

                    if (composed != null) {
                        String combinedHash = composed.getCombinedHash();
                        String virtualId = VirtualItemRegistry.generateVirtualId(itemId, combinedHash);
                        
                        // Create the virtual item base using the resolved overrides
                        ItemBase virtualItem = virtualItemRegistry.getOrCreateVirtualItemBase(
                                itemId,
                                virtualId,
                                composed.getNameOverride(),
                                composed.getVisualOverrides()
                        );

                        if (virtualItem != null) {
                            // Create a new ItemWithAllMetadata with:
                            // - Virtual Item ID
                            // - Virtual Max Durability (if overridden)
                            // - Original Quantity & Current Durability (preserve state)
                            ItemWithAllMetadata newItem = new ItemWithAllMetadata(
                                    virtualItem.id, // Use the virtual ID
                                    notification.item.quantity,
                                    notification.item.durability,
                                    virtualItem.durability, // Apply visual max durability override
                                    notification.item.overrideDroppedItemAnimation,
                                    notification.item.metadata
                            );
                            
                            // Replace the item in the packet
                            notification.item = newItem;

                            // Also update the message param "item" which shows the name
                            // We must update the "item" entry in the messageParams map
                            if (notification.message.messageParams != null && notification.message.messageParams.containsKey("item")) {
                                 // Use the updated name key from the virtual item
                                String nameKey = virtualItem.translationProperties != null ? virtualItem.translationProperties.name : itemId;
                                // Create a new FormattedMessage for the name
                                // We use Message helper to easily create a translation message
                                FormattedMessage nameMessage = Message.translation(nameKey).getFormattedMessage();
                                
                                // Replace the param
                                notification.message.messageParams.put("item", nameMessage);
                            }
                        }
                    }
                }
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

            org.herolias.tooltips.api.ItemVisualOverrides visualOverrides = null;
            // Try to recover visual overrides from the registry buffer if possible
            // We can extract the hash from the virtualId
            int separatorIndex = virtualId.indexOf(VirtualItemRegistry.VIRTUAL_SEPARATOR);
            if (separatorIndex > 0) {
                String hash = virtualId.substring(separatorIndex + VirtualItemRegistry.VIRTUAL_SEPARATOR.length());
                TooltipRegistry.ComposedTooltip composed = tooltipRegistry.getComposed(hash);
                if (composed != null) {
                    visualOverrides = composed.getVisualOverrides();
                }
            }

            // The virtual item base is already cached with the correct name override
            // from when it was first created via processSection.
            // If it's not in cache, we attempt to reconstruct it.
            // Note: If visualOverrides is null (e.g. server restart cleared cache), we might lose visuals here.
            // This is acceptable for now vs crashing or strict persistence.
            ItemBase virtualBase = virtualItemRegistry.getOrCreateVirtualItemBase(
                    itemId, virtualId, null, visualOverrides);

            if (virtualBase != null) {
                newVirtualItems.put(virtualId, virtualBase);
                if (cachedDesc != null) {
                    translations.put(descKey, cachedDesc);
                }
            }
            return virtualId;
        }

        return null;
    }



    // ───────────────────────────────────────────────────────────────────────
    //  Entity Update Processing (Visual Reversion Fix)
    // ───────────────────────────────────────────────────────────────────────

    /**
     * Inspects outbound EntityUpdates for two purposes:
     * <ol>
     *   <li><b>Player equipment:</b> If an update targets the local player and
     *       modifies Equipment, we replace the real Item ID with the virtual ID
     *       from the active hotbar/utility slot to prevent visual reversion.</li>
     *   <li><b>Dropped item entities:</b> If any entity carries an {@code Item(5)}
     *       component, we resolve the virtual ID via the tooltip registry using
     *       the item's {@code itemId + metadata}. This makes dropped items on the
     *       ground render with overridden models/textures.</li>
     * </ol>
     */
    private void processEntityUpdates(@Nonnull PlayerRef playerRef, @Nonnull EntityUpdates packet) {
        if (packet.updates == null || packet.updates.length == 0) return;

        Integer localEntityId = playerEntityIds.get(playerRef.getUuid());
        
        // EntityStore for reverse lookup (Network ID -> Entity Ref)
        // We act largely on the recipient's world view
        EntityStore entityStore = null;
        if (playerRef.isValid() && playerRef.getReference() != null) {
            Store<EntityStore> store = playerRef.getReference().getStore();
            if (store != null) {
                entityStore = store.getExternalData();
            }
        }

        Map<String, ItemBase> newVirtualItems = new LinkedHashMap<>();
        Map<String, String> translations = new LinkedHashMap<>();

        for (EntityUpdate update : packet.updates) {
            if (update.updates == null) continue;

            for (ComponentUpdate comp : update.updates) {
                // ── Player Equipment (visual reversion fix & multiplayer broadcast) ──
                if (comp.equipment != null) {
                    if (localEntityId != null && update.networkId == localEntityId) {
                        // Self-update: The player is updating their own equipment.
                        // We use the player's own UUID to look up their active slot.
                        processEquipmentUpdate(playerRef, playerRef, comp.equipment, newVirtualItems, translations);
                    } else if (localEntityId != null && update.networkId != localEntityId && entityStore != null) {
                        // Remote-update: Another entity is updating equipment.
                        // We need to find out WHO this entity is.
                        Ref<EntityStore> entityRef = entityStore.getRefFromNetworkId(update.networkId);
                        if (entityRef != null) {
                            // Check if it's a player
                            PlayerRef remotePlayerRef = entityRef.getStore().getComponent(entityRef, PlayerRef.getComponentType());
                            if (remotePlayerRef != null) {
                                // It IS a player! We can now resolve their active slot and overrides.
                                processEquipmentUpdate(playerRef, remotePlayerRef, comp.equipment, newVirtualItems, translations);
                            }
                        }
                    }
                }

                // ── Dropped Item entities (model/texture override) ──
                if (comp.type == ComponentUpdateType.Item && comp.item != null
                        && comp.item.itemId != null && !comp.item.itemId.isEmpty()
                        && !VirtualItemRegistry.isVirtualId(comp.item.itemId)) {

                    TooltipRegistry.ComposedTooltip composed = tooltipRegistry.compose(
                            comp.item.itemId, comp.item.metadata);
                    if (composed != null && composed.getVisualOverrides() != null
                            && !composed.getVisualOverrides().isEmpty()) {

                        String baseItemId = comp.item.itemId;

                        // For dropped items, always resolve an effective name so the
                        // virtual ItemBase uses a virtual name key in its
                        // translationProperties — otherwise the client can't resolve
                        // the name on pickup.
                        String effectiveName = composed.getNameOverride();
                        if (effectiveName == null) {
                            effectiveName = virtualItemRegistry.getOriginalName(
                                    baseItemId, playerRef.getLanguage());
                        }

                        String virtualId = virtualItemRegistry.generateVirtualId(
                                baseItemId, composed.getCombinedHash());
                        ItemBase virtualBase = virtualItemRegistry.getOrCreateVirtualItemBase(
                                baseItemId, virtualId, effectiveName,
                                composed.getVisualOverrides());

                        if (virtualBase != null) {
                            comp.item.itemId = virtualId;
                            newVirtualItems.put(virtualId, virtualBase);

                            String descKey = VirtualItemRegistry.getVirtualDescriptionKey(virtualId);
                            String originalDesc = virtualItemRegistry.getOriginalDescription(
                                    baseItemId, playerRef.getLanguage());
                            String enrichedDesc = composed.buildDescription(originalDesc);
                            translations.put(descKey, enrichedDesc);

                            // Always send the name translation under the virtual key
                            String nameKey = VirtualItemRegistry.getVirtualNameKey(virtualId);
                            if (effectiveName != null) {
                                translations.put(nameKey, effectiveName);
                            }
                        }
                    }
                }
            }
        }

        // Send auxiliary packets (UpdateItems + translations) if any items were virtualised
        if (!newVirtualItems.isEmpty()) {
            sendAuxiliaryPackets(playerRef, newVirtualItems, translations);
        }
    }

    private void processEquipmentUpdate(@Nonnull PlayerRef recipientRef,
                                        @Nonnull PlayerRef observedPlayerRef,
                                        @Nonnull Equipment equipment,
                                        @Nonnull Map<String, ItemBase> newVirtualItems,
                                        @Nonnull Map<String, String> translations) {
        UUID observedPlayerUuid = observedPlayerRef.getUuid();
        boolean rightHandVirtualized = false;
        boolean leftHandVirtualized = false;
        
        // If the equipment update sets a right-hand item that is NOT virtual,
        // we check if it matches the base ID of the virtual item in the ACTIVE slot
        // of the OBSERVED player.
        if (equipment.rightHandItemId != null && !VirtualItemRegistry.isVirtualId(equipment.rightHandItemId)) {
            
            Integer slotObj = playerActiveHotbarSlots.get(observedPlayerUuid);
            // Default to slot 0 if unknown (reasonable fallback for initial join)
            int slot = slotObj != null ? slotObj : 0;

            String virtualId = virtualItemRegistry.getSlotVirtualId(observedPlayerUuid, "hotbar:" + slot);
            if (virtualId != null) {
                String baseId = VirtualItemRegistry.getBaseItemId(virtualId);
                // If the packet assumes the base item, but we know it's virtual, swap it.
                if (baseId != null && baseId.equals(equipment.rightHandItemId)) {
                    equipment.rightHandItemId = virtualId;
                    addVirtualEquipmentItem(recipientRef, baseId, virtualId, newVirtualItems, translations);
                    rightHandVirtualized = true;

                } else {
                     // Fallback: If the active slot didn't match, scan the entire hotbar.
                     for (int i = 0; i < 9; i++) {
                         if (i == slot) continue; // Already checked
                         String otherVirtualId = virtualItemRegistry.getSlotVirtualId(observedPlayerUuid, "hotbar:" + i);
                         if (otherVirtualId != null) {
                             String otherBaseId = VirtualItemRegistry.getBaseItemId(otherVirtualId);
                             if (otherBaseId != null && otherBaseId.equals(equipment.rightHandItemId)) {
                                 equipment.rightHandItemId = otherVirtualId;
                                 addVirtualEquipmentItem(recipientRef, otherBaseId, otherVirtualId, newVirtualItems, translations);
                                 rightHandVirtualized = true;
                                 break;
                             }
                         }
                     }
                }
            } else {
                 // Fallback: If the expected slot was empty/unknown, scan the hotbar anyway.
                 for (int i = 0; i < 9; i++) {
                     if (i == slot) continue;
                     String otherVirtualId = virtualItemRegistry.getSlotVirtualId(observedPlayerUuid, "hotbar:" + i);
                     if (otherVirtualId != null) {
                         String otherBaseId = VirtualItemRegistry.getBaseItemId(otherVirtualId);
                         if (otherBaseId != null && otherBaseId.equals(equipment.rightHandItemId)) {
                             equipment.rightHandItemId = otherVirtualId;
                             addVirtualEquipmentItem(recipientRef, otherBaseId, otherVirtualId, newVirtualItems, translations);
                             rightHandVirtualized = true;
                             break;
                         }
                     }
                 }
            }
        }

        if (!rightHandVirtualized) {
            rightHandVirtualized = tryVirtualizeFromObservedInventory(
                    recipientRef, observedPlayerRef, false, equipment, newVirtualItems, translations);
        }

        // Fix for off-hand (left hand) items not showing visual overrides.
        // The off-hand slot maps to "utility:0" in our registry.
        if (equipment.leftHandItemId != null && !VirtualItemRegistry.isVirtualId(equipment.leftHandItemId)) {
            // The off-hand is always utility slot 0 of the observed player
            String virtualId = virtualItemRegistry.getSlotVirtualId(observedPlayerUuid, "utility:0");

            if (virtualId != null) {
                String baseId = VirtualItemRegistry.getBaseItemId(virtualId);
                if (baseId != null && baseId.equals(equipment.leftHandItemId)) {
                    equipment.leftHandItemId = virtualId;
                    addVirtualEquipmentItem(recipientRef, baseId, virtualId, newVirtualItems, translations);
                    leftHandVirtualized = true;
                }
            }
        }

        if (!leftHandVirtualized) {
            tryVirtualizeFromObservedInventory(
                    recipientRef, observedPlayerRef, true, equipment, newVirtualItems, translations);
        }
    }

    private boolean tryVirtualizeFromObservedInventory(@Nonnull PlayerRef recipientRef,
                                                       @Nonnull PlayerRef observedPlayerRef,
                                                       boolean leftHand,
                                                       @Nonnull Equipment equipment,
                                                       @Nonnull Map<String, ItemBase> newVirtualItems,
                                                       @Nonnull Map<String, String> translations) {
        Player observedPlayer = getObservedPlayerComponent(observedPlayerRef);
        if (observedPlayer == null) return false;

        ItemStack stack = leftHand
                ? observedPlayer.getInventory().getUtilityItem()
                : observedPlayer.getInventory().getItemInHand();
        if (stack == null) return false;

        String stackItemId = stack.getItemId();
        if (stackItemId == null || stackItemId.isEmpty() || VirtualItemRegistry.isVirtualId(stackItemId)) {
            return false;
        }

        String equipmentItemId = leftHand ? equipment.leftHandItemId : equipment.rightHandItemId;
        if (equipmentItemId == null || equipmentItemId.isEmpty() || VirtualItemRegistry.isVirtualId(equipmentItemId)) {
            return false;
        }

        // Guard against desynced snapshots: only rewrite when equipment and inventory agree.
        if (!stackItemId.equals(equipmentItemId)) {
            return false;
        }

        TooltipRegistry.ComposedTooltip composed = tooltipRegistry.compose(stackItemId, stack.toPacket().metadata);
        if (composed == null) return false;

        String virtualId = VirtualItemRegistry.generateVirtualId(stackItemId, composed.getCombinedHash());
        if (leftHand) {
            equipment.leftHandItemId = virtualId;
            virtualItemRegistry.trackSlotVirtualId(observedPlayerRef.getUuid(), "utility:0", virtualId);
        } else {
            equipment.rightHandItemId = virtualId;
            Integer slot = playerActiveHotbarSlots.get(observedPlayerRef.getUuid());
            if (slot != null && slot >= 0) {
                virtualItemRegistry.trackSlotVirtualId(observedPlayerRef.getUuid(), "hotbar:" + slot, virtualId);
            }
        }

        addVirtualEquipmentItem(recipientRef, stackItemId, virtualId, newVirtualItems, translations);

        String descKey = VirtualItemRegistry.getVirtualDescriptionKey(virtualId);
        if (!translations.containsKey(descKey)) {
            String originalDesc = virtualItemRegistry.getOriginalDescription(stackItemId, recipientRef.getLanguage());
            String enrichedDesc = composed.buildDescription(originalDesc);
            translations.put(descKey, enrichedDesc);
            virtualItemRegistry.cacheDescription(virtualId, enrichedDesc);
        }
        if (composed.getNameOverride() != null) {
            translations.put(VirtualItemRegistry.getVirtualNameKey(virtualId), composed.getNameOverride());
        }
        return true;
    }

    @Nullable
    private Player getObservedPlayerComponent(@Nonnull PlayerRef observedPlayerRef) {
        Ref<EntityStore> ref = observedPlayerRef.getReference();
        if (ref == null || !ref.isValid()) return null;
        Store<EntityStore> store = ref.getStore();
        if (store == null) return null;
        return store.getComponent(ref, Player.getComponentType());
    }

    private void addVirtualEquipmentItem(@Nonnull PlayerRef recipientRef,
                                         @Nonnull String baseId,
                                         @Nonnull String virtualId,
                                         @Nonnull Map<String, ItemBase> newVirtualItems,
                                         @Nonnull Map<String, String> translations) {
        ItemBase virtualBase = resolveVirtualBaseForEquipment(recipientRef, baseId, virtualId);
        if (virtualBase == null) return;

        newVirtualItems.put(virtualId, virtualBase);

        String descKey = VirtualItemRegistry.getVirtualDescriptionKey(virtualId);
        String cachedDesc = virtualItemRegistry.getCachedDescription(virtualId);
        if (cachedDesc != null) {
            translations.put(descKey, cachedDesc);
        }

        // If this virtual item uses a virtual name key, ensure the recipient has the translation.
        String nameKey = VirtualItemRegistry.getVirtualNameKey(virtualId);
        if (virtualBase.translationProperties != null && nameKey.equals(virtualBase.translationProperties.name)) {
            String effectiveName = resolveVirtualName(recipientRef, baseId, virtualId);
            if (effectiveName != null) {
                translations.put(nameKey, effectiveName);
            }
        }
    }

    @Nullable
    private ItemBase resolveVirtualBaseForEquipment(@Nonnull PlayerRef recipientRef,
                                                    @Nonnull String baseId,
                                                    @Nonnull String virtualId) {
        TooltipRegistry.ComposedTooltip composed = findComposedByVirtualId(virtualId);
        org.herolias.tooltips.api.ItemVisualOverrides visualOverrides =
                composed != null ? composed.getVisualOverrides() : null;
        String nameOverride = composed != null ? composed.getNameOverride() : null;

        ItemBase virtualBase = virtualItemRegistry.getOrCreateVirtualItemBase(
                baseId, virtualId, nameOverride, visualOverrides);

        if (virtualBase != null) {
            return virtualBase;
        }

        // Fallback: when we only have a "named" variant cached (e.g. dropped items),
        // try again with the resolved original name for this recipient language.
        String originalName = virtualItemRegistry.getOriginalName(baseId, recipientRef.getLanguage());
        if (originalName != null) {
            return virtualItemRegistry.getOrCreateVirtualItemBase(baseId, virtualId, originalName, null);
        }
        return null;
    }

    @Nullable
    private String resolveVirtualName(@Nonnull PlayerRef recipientRef,
                                      @Nonnull String baseId,
                                      @Nonnull String virtualId) {
        TooltipRegistry.ComposedTooltip composed = findComposedByVirtualId(virtualId);
        if (composed != null && composed.getNameOverride() != null) {
            return composed.getNameOverride();
        }
        return virtualItemRegistry.getOriginalName(baseId, recipientRef.getLanguage());
    }

    @Nullable
    private TooltipRegistry.ComposedTooltip findComposedByVirtualId(@Nonnull String virtualId) {
        int separatorIndex = virtualId.indexOf(VirtualItemRegistry.VIRTUAL_SEPARATOR);
        if (separatorIndex <= 0) return null;
        String hash = virtualId.substring(separatorIndex + VirtualItemRegistry.VIRTUAL_SEPARATOR.length());
        return tooltipRegistry.getComposed(hash);
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
                    baseItemId, virtualId, composed.getNameOverride(), composed.getVisualOverrides());
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
