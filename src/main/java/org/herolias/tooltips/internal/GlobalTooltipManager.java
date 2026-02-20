package org.herolias.tooltips.internal;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.UpdateType;
import com.hypixel.hytale.protocol.packets.assets.UpdateTranslations;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages global tooltip properties that affect all items of a specific base type,
 * without using virtual IDs. This is useful for system-wide tooltips that everyone should see.
 */
public class GlobalTooltipManager {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final VirtualItemRegistry virtualItemRegistry;

    // Track additive and replace lines separately. Replace takes precedence over additive.
    private final Map<String, List<String>> additiveLines = new ConcurrentHashMap<>();
    private final Map<String, List<String>> replacedLines = new ConcurrentHashMap<>();

    public GlobalTooltipManager(@Nonnull VirtualItemRegistry virtualItemRegistry) {
        this.virtualItemRegistry = virtualItemRegistry;
    }

    /**
     * Appends a line to the global tooltip of an item type.
     * @param baseItemId the base item ID
     * @param line the line to add
     */
    public void addGlobalLine(@Nonnull String baseItemId, @Nonnull String line) {
        additiveLines.computeIfAbsent(baseItemId, k -> new ArrayList<>()).add(line);
        broadcastUpdates(Collections.singleton(baseItemId));
    }

    /**
     * Replaces the global tooltip of an item type with the given lines.
     * @param baseItemId the base item ID
     * @param lines the lines to replace the description with
     */
    public void replaceGlobalTooltip(@Nonnull String baseItemId, @Nonnull String[] lines) {
        replacedLines.put(baseItemId, Arrays.asList(lines));
        broadcastUpdates(Collections.singleton(baseItemId));
    }

    /**
     * Clears all global tooltip overrides for this base item type.
     * @param baseItemId the base item ID
     */
    public void clearGlobalTooltips(@Nonnull String baseItemId) {
        boolean removedAdd = additiveLines.remove(baseItemId) != null;
        boolean removedRep = replacedLines.remove(baseItemId) != null;
        if (removedAdd || removedRep) {
            broadcastUpdates(Collections.singleton(baseItemId));
        }
    }

    /**
     * Invoked when a player joins or needs a full refresh of all global items.
     * @param playerRef the player reference
     */
    public void sendAllUpdates(@Nonnull PlayerRef playerRef) {
        Set<String> allIds = new HashSet<>();
        allIds.addAll(additiveLines.keySet());
        allIds.addAll(replacedLines.keySet());
        sendUpdates(playerRef, allIds);
    }

    /**
     * Broadcasts updates to all online players.
     */
    private void broadcastUpdates(@Nonnull Set<String> baseItemIds) {
        if (Universe.get() == null) return;
        
        for (PlayerRef playerRef : Universe.get().getPlayers()) {
            if (playerRef == null || !playerRef.isValid()) continue;
            sendUpdates(playerRef, baseItemIds);
        }
    }

    /**
     * Sends the specific set of base item global updates to a given player.
     */
    private void sendUpdates(@Nonnull PlayerRef playerRef, @Nonnull Set<String> baseItemIds) {
        if (baseItemIds.isEmpty()) return;
        
        String locale = playerRef.getLanguage();
        if (locale == null || locale.isEmpty()) locale = "en-US";

        Map<String, String> translations = new HashMap<>();

        for (String baseItemId : baseItemIds) {
            String translationKey = virtualItemRegistry.getItemDescriptionKey(baseItemId);
            String computed = getGlobalDescription(baseItemId, locale);
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

    /**
     * Injects the globally overridden tooltips into the given translation map.
     * Often used when the server sends UpdateTranslations(Init).
     */
    public void injectIntoInitPacket(@Nonnull UpdateTranslations packet, @Nullable String locale) {
        if (packet.translations == null) return;
        
        if (locale == null || locale.isEmpty()) locale = "en-US";
        
        Set<String> allIds = new HashSet<>();
        allIds.addAll(additiveLines.keySet());
        allIds.addAll(replacedLines.keySet());
        
        for (String baseItemId : allIds) {
            String key = virtualItemRegistry.getItemDescriptionKey(baseItemId);
            String computed = getGlobalDescription(baseItemId, locale);
            if (computed != null) {
                packet.translations.put(key, computed);
            }
        }
    }

    /**
     * Gets the full description for an item incorporating global updates and original text.
     */
    @Nullable
    public String getGlobalDescription(@Nonnull String baseItemId, @Nonnull String locale) {
        // Replace overrides completely overwrite everything else
        List<String> replace = replacedLines.get(baseItemId);
        if (replace != null) {
            return String.join("\n", replace);
        }
        
        List<String> add = additiveLines.get(baseItemId);
        if (add != null && !add.isEmpty()) {
            String original = virtualItemRegistry.getOriginalDescription(baseItemId, locale);
            StringBuilder sb = new StringBuilder();
            
            if (original != null && !original.isEmpty()) {
                sb.append(original);
                sb.append("\n\n");
            }
            sb.append(String.join("\n", add));
            return sb.toString();
        }
        
        // If there's neither replace nor add (e.g. cleared), return the original text directly.
        return virtualItemRegistry.getOriginalDescription(baseItemId, locale);
    }
}
